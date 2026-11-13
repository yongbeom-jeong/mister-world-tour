"""동시성 확인 (정원·기념품 재고·취소·문자 재발송). PostgreSQL 에서만 실행한다.

SQLite 는 select_for_update 잠금을 무시하므로 개발 PC 에서는 건너뛴다.
배포 서버(PostgreSQL)에서: .venv/bin/python manage.py test apps.travel.test_concurrency --noinput

여러 스레드가 동시에 같은 작업을 하게 하고, 잠금 안쪽의 인원 조회를 조금 늦춰 경쟁 구간을 넓힌다.
잠금이 빠지면 같은 인원 수를 동시에 읽어 정원 초과·확정 후 취소 같은 결과가 나와 테스트가 실패한다.
"""

import random
import threading
import time
import unittest
from unittest import mock

from django.db import close_old_connections, connection
from django.test import TransactionTestCase, override_settings

from apps.members.models import Member
from apps.sms import services as sms_service
from apps.sms.models import SMSLog
from apps.souvenir.models import Souvenir, StockIssue

from . import booking
from . import configuration as cfg
from .choices import DepartureStatus, Theme
from .models import TravelApplication
from .test_booking import customer, make_product

DELAY = 0.05  # 잠금 안쪽 지연 (초)
_real_count = booking.current_traveler_count


def _slow_count(product_id):
    n = _real_count(product_id)
    time.sleep(DELAY)
    return n


_real_block = booking.cancel_block_reason


def _slow_block(app, today=None):
    reason = _real_block(app, today)
    time.sleep(DELAY)  # 취소 가능 판단 후 저장 전까지 간격을 넓힌다
    return reason


def run_together(funcs):
    """함수들을 동시에 시작해 결과(값 또는 예외)를 순서대로 돌려준다. 스레드마다 DB 연결을 따로 쓴다."""
    barrier = threading.Barrier(len(funcs))
    results = [None] * len(funcs)

    def worker(i, f):
        try:
            barrier.wait()
            results[i] = f()
        except Exception as e:  # 업무 거부(BookingError 등)도 결과로 모은다
            results[i] = e
        finally:
            close_old_connections()
            connection.close()

    threads = [threading.Thread(target=worker, args=(i, f)) for i, f in enumerate(funcs)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(30)
    return results


def rows(member, *companions):
    return [{"name": member.name, "contact": member.contact}] + [{"name": n, "contact": ""} for n in companions]


@unittest.skipUnless(connection.vendor == "postgresql", "동시성 잠금은 PostgreSQL 에서만 확인할 수 있습니다")
@override_settings(SMS_CLIENT="apps.sms.services.ConsoleSMSClient")
class BookingConcurrencyTests(TransactionTestCase):
    def setUp(self):
        # TransactionTestCase 는 테스트가 끝날 때 표를 비운다 (첫 테스트에는 마이그레이션 기본 품목이 남아 있음)
        self.ball, _ = Souvenir.objects.update_or_create(
            name="골프공", defaults={"theme": Theme.GOLF, "stock_quantity": 100, "is_active": True})
        Souvenir.objects.filter(theme=Theme.GOLF).exclude(pk=self.ball.pk).delete()
        sms_service.ConsoleSMSClient.sent = []

    def members(self, n, prefix):
        return [customer(f"{prefix}{i}", f"{prefix}{i}", f"010-3000-{i:04d}") for i in range(n)]

    def review(self, member, product, *companions):
        return booking.review_application(member, product.pk, cfg.default_configuration(product),
                                          rows(member, *companions))

    def submit_all(self, product, members, companions_each):
        reviews = [self.review(m, product, *[f"동반{k}" for k in range(companions_each)]) for m in members]
        with mock.patch.object(booking, "current_traveler_count", _slow_count):
            return run_together([lambda m=m, r=r: booking.submit_travel_application(m, r)
                                 for m, r in zip(members, reviews)])

    def test_capacity_never_exceeded(self):
        """2명씩 6건이 동시에 신청 → 정원 10명까지만 저장, 1건은 정원 초과로 거부."""
        product = make_product(Theme.GOLF)
        results = self.submit_all(product, self.members(6, "cap"), companions_each=1)

        ok = [r for r in results if isinstance(r, booking.ApplicationResult)]
        rejected = [r for r in results if isinstance(r, booking.BookingError)]
        self.assertEqual((len(ok), len(rejected)), (5, 1), results)
        self.assertEqual(booking.current_traveler_count(product.pk), 10)
        product.refresh_from_db()
        self.assertEqual(product.departure_status, DepartureStatus.CONFIRMED)
        # 확정은 정확히 한 번 (확정 문자를 두 번 보내지 않음)
        self.assertEqual(sum(r.newly_confirmed for r in ok), 1)
        # 기념품은 저장된 여행자 수만큼만 차감
        self.ball.refresh_from_db()
        self.assertEqual(self.ball.stock_quantity, 90)
        self.assertEqual(sum(StockIssue.objects.values_list("quantity", flat=True)), 10)

    def test_souvenir_stock_never_negative(self):
        """재고 5개에 1명씩 8건이 동시에 신청 → 5건만 저장, 재고 0, 음수 없음."""
        Souvenir.objects.filter(pk=self.ball.pk).update(stock_quantity=5)
        product = make_product(Theme.GOLF)
        results = self.submit_all(product, self.members(8, "stock"), companions_each=0)

        ok = [r for r in results if isinstance(r, booking.ApplicationResult)]
        self.assertEqual(len(ok), 5, results)
        self.assertTrue(all(isinstance(r, (booking.ApplicationResult, booking.BookingError)) for r in results), results)
        self.ball.refresh_from_db()
        self.assertEqual(self.ball.stock_quantity, 0)
        self.assertEqual(sum(StockIssue.objects.values_list("quantity", flat=True)), 5)
        self.assertEqual(booking.current_traveler_count(product.pk), 5)

    def test_cancel_and_third_traveler_race(self):
        """2명 모인 상품에서 '1번 신청 취소'와 '3번째 신청'이 동시에 → 확정된 신청이 취소되는 일은 없다.

        순서에 따라 결과가 둘 중 하나여야 한다.
          취소 먼저: 취소 성공, 여행자 2명, 미확정
          신청 먼저: 출발 확정(3명), 취소는 '확정된 신청' 으로 거부
        """
        outcomes = set()
        for i in range(12):
            product = make_product(Theme.GOLF, name=f"경쟁 {i}")
            a, b, c = self.members(3, f"race{i}x")
            first = booking.submit_travel_application(a, self.review(a, product)).application
            booking.submit_travel_application(b, self.review(b, product))
            third = self.review(c, product)

            def cancel():
                time.sleep(random.uniform(0, DELAY))
                return booking.cancel_application(a, first.pk)

            def apply_third():
                time.sleep(random.uniform(0, DELAY))
                return booking.submit_travel_application(c, third)

            with mock.patch.object(booking, "current_traveler_count", _slow_count),                  mock.patch.object(booking, "cancel_block_reason", _slow_block):
                cancel_result, apply_result = run_together([cancel, apply_third])

            product.refresh_from_db()
            first = TravelApplication.objects.get(pk=first.pk)
            count = booking.current_traveler_count(product.pk)
            self.assertIsInstance(apply_result, booking.ApplicationResult, apply_result)
            if first.is_cancelled:
                self.assertEqual((count, product.departure_status), (2, DepartureStatus.PENDING))
                outcomes.add("취소 먼저")
            else:
                self.assertIsInstance(cancel_result, booking.BookingError)
                self.assertEqual((count, product.departure_status), (3, DepartureStatus.CONFIRMED))
                outcomes.add("신청 먼저")
        print(f"\n  취소·3번째 신청 경쟁 결과: {sorted(outcomes)}")


class CountingClient:
    """보낸 횟수를 세는 느린 가짜 문자 클라이언트."""

    def __init__(self):
        self.calls = 0
        self.lock = threading.Lock()

    def send_sms(self, contact, message):
        with self.lock:
            self.calls += 1
        time.sleep(0.2)


@unittest.skipUnless(connection.vendor == "postgresql", "동시성 잠금은 PostgreSQL 에서만 확인할 수 있습니다")
class SMSResendConcurrencyTests(TransactionTestCase):
    def test_two_staff_resend_same_log_once(self):
        """같은 실패 기록을 두 직원이 동시에 재발송 → 실제 발송은 한 번, 다른 한 명은 거부."""
        s1 = Member.objects.create_user(username="staffa", password="x", name="직원A", role=Member.Role.STAFF)
        s2 = Member.objects.create_user(username="staffb", password="x", name="직원B", role=Member.Role.STAFF)
        log = SMSLog.objects.create(recipient_name="앨리스", contact="010-1000-0001", message="m",
                                    status=SMSLog.Status.FAILED, attempt_count=4)
        client = CountingClient()
        results = run_together([
            lambda: sms_service.resend_logged_sms(log.pk, s1.pk, client=client),
            lambda: sms_service.resend_logged_sms(log.pk, s2.pk, client=client),
        ])
        self.assertEqual(client.calls, 1, results)
        self.assertEqual(sum(isinstance(r, sms_service.SMSResendError) for r in results), 1, results)
        log.refresh_from_db()
        self.assertEqual((log.status, log.resend_count), (SMSLog.Status.SUCCESS, 1))
