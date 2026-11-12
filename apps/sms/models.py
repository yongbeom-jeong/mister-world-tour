from datetime import timedelta

from django.conf import settings
from django.db import models
from django.utils import timezone

STALE_PENDING_MINUTES = 5  # 발송 중 상태로 이만큼 지나면 처리 도중 멈춘 것으로 보고 직원이 확인·재발송


class SMSLog(models.Model):
    """문자 1건(수신자 1명)의 발송 기록. 자동 재시도와 직원 재발송 결과를 같은 행에 누적한다."""

    class Status(models.TextChoices):
        PENDING = "PENDING", "발송 중"
        SUCCESS = "SUCCESS", "성공"
        FAILED = "FAILED", "실패"
        UNKNOWN = "UNKNOWN", "결과 확인 필요"
        INVALID = "INVALID", "정보 누락"

    # 수신 고객·상품은 다른 앱의 모델이라 값으로 보관한다 (지워져도 기록은 남음)
    member_id = models.PositiveIntegerField("수신 고객 id", null=True, blank=True)
    recipient_name = models.CharField("수신자", max_length=50)
    contact = models.CharField("연락처", max_length=20, blank=True)
    product_id = models.PositiveIntegerField("상품 id", null=True, blank=True)
    product_name = models.CharField("상품", max_length=100, blank=True)
    message = models.TextField("문구")
    status = models.CharField("상태", max_length=10, choices=Status.choices, default=Status.PENDING)
    attempt_count = models.PositiveSmallIntegerField("외부 호출 횟수", default=0)
    last_error = models.TextField("마지막 오류", blank=True)
    provider_ref = models.CharField("업체 접수 번호", max_length=100, blank=True)  # 솔라피 groupId
    resend_count = models.PositiveSmallIntegerField("직원 재발송 횟수", default=0)
    resent_by = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.SET_NULL, null=True, blank=True,
                                  related_name="+", verbose_name="마지막 재발송 직원")
    created_at = models.DateTimeField("요청 시점", auto_now_add=True)
    updated_at = models.DateTimeField("마지막 변경", auto_now=True)

    class Meta:
        verbose_name = "문자 발송 기록"
        verbose_name_plural = "문자 발송 기록"
        ordering = ["-created_at", "-id"]
        indexes = [models.Index(fields=["status", "-created_at"])]

    def __str__(self) -> str:
        return f"{self.recipient_name} {self.get_status_display()}"

    @property
    def is_stale_pending(self) -> bool:
        return (self.status == self.Status.PENDING
                and self.updated_at < timezone.now() - timedelta(minutes=STALE_PENDING_MINUTES))

    @property
    def needs_attention(self) -> bool:
        return self.status in (self.Status.FAILED, self.Status.UNKNOWN) or self.is_stale_pending

    @property
    def may_be_delivered(self) -> bool:
        """이미 받았을 수 있는 상태 (재발송하면 중복 수신 가능)."""
        return self.status == self.Status.UNKNOWN or self.is_stale_pending
