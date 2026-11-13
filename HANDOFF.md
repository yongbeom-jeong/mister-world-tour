# 작업 인계 (HANDOFF)

마지막 갱신: 2026-11-13. 다음 세션은 이 파일 → `AGENTS.md` → `README.md` 순서로 읽고 시작한다.
일정: **최종 발표·시연 2026-11-24**. 지금은 **구현과 배포만** 다룬다 (문서·측정·시연 준비는 팀원이 마지막에 따로 처리).

## 1. 현재 상태

- **구현·배포 완료.** 남은 일은 팀원이 할 문서 수정·산출물, 발표 후 AWS 자원 정리
- 2026-11-13: 화면 리뉴얼(PR #15), AWS 배포, PostgreSQL 동시성 확인
  - 사이트 **https://3-35-38-138.sslip.io/** (EC2 `mwt-server` t3.small, 서울, 탄력적 IP 3.35.38.138)
  - 접속: `ssh -i ~/.ssh/mwt-key.pem ubuntu@3.35.38.138` (키는 정용범 PC 에만). 보안 그룹 `mwt-sg` 의 SSH 는 정용범 집 IP 만 허용 → 다른 곳에서는 IP 추가 필요
  - 서버 `.env`: 솔라피 실제 발송 + `SMS_ALLOWED_NUMBERS`, 직원 가입 코드는 서버에서 무작위 생성(설치 출력 참고, 로컬 기본값과 다름)
  - 동시성: `apps/travel/test_concurrency.py` (PostgreSQL 에서만 실행, 개발 PC 에서는 건너뜀). 서버에서 4개 통과, 잠금을 끄면 4개 모두 실패하는 것도 확인
- 문서와 구현 기준(2026-11-12 결정): 문서대로 하면 기능이 깨지거나 위험한 항목은 구현을 유지하고 문서를 고친다. 나머지는 구현을 문서에 맞춤
  - 문서에 맞춘 구현: 직원의 기본 구성 선택(UC6 (3)), 허니문·효도 옵션 그랜드 이상, 가입·금액 계산 DB 오류 안내, 브라우저 음성 실패 기록, 뷰의 ORM 직접 조회 제거, 입고 결과 7가지 상태, 문자 기록 선 생성
  - 회의용 불일치 목록(문서 수정 대상): claude.ai Artifact '구현·문서 불일치 목록' (링크는 정용범에게)
- 2026-11-12 세션에서 한 일 (PR #13, 그리고 이번 PR)
  - 문자 발송 묶음 시간 한도 `SMS_BATCH_TIME_LIMIT`(40초), 같은 요청 키 재제출 시 구성·여행자까지 비교, 신청 저장 중 DB 오류 시 반영 여부 재조회 후 안내
  - `manage.py measure_evaluation` (음성 명령 20개 해석·조회 응답 시간 측정 도구, 필요할 때만)
  - **신청 취소**: 출발 확정 전·출발일 전에만. 허니문은 신청과 함께 확정되므로 취소 불가 (`TravelApplication.cancelled_at`)
  - **완료 여행 이력 자동 생성**: 출발 확정·미취소 신청이 여행 마지막 날을 지나면 이력 1건 (이력 조회·금액 계산 때 반영, 일괄 `manage.py complete_trips`)
  - **문자 발송 기록** `SMSLog` + 직원 화면 `/sms/staff/logs/` (실패·결과 불명 재발송)
  - **기념품 관리** `/souvenir/staff/souvenirs/` (수정·삭제·차감 내역). 내역 있는 품목 삭제는 숨김 처리
- GitHub CLI: `C:\Program Files\GitHub CLI\gh.exe` 설치·로그인 완료 (Git Bash 에서는 PATH 에 없을 수 있어 전체 경로로 실행)

## 2. 환경 메모 (비밀값은 각 PC·서버의 `.env`에만)

- **이 PC(시연 PC)의 `.env`**: `SMS_CLIENT=apps.sms.solapi.SolapiSMSClient`, 솔라피 Key·Secret·발신번호 설정됨, `SMS_ALLOWED_NUMBERS`에 팀원 3명 번호
- 솔라피 API Key 는 **허용 IP 제한**이 있다. 현재 이 PC의 공인 IP만 등록됨
  - 다른 팀원 PC: `.env`에서 `SMS_CLIENT` 를 빼서 기본 가짜 발송(콘솔 로그)으로 개발
  - 집·학교 IP가 바뀌면 시험 발송이 실패 → 솔라피 콘솔에서 IP 추가
- 솔라피 잔액: 가입 300포인트(단문 18원 기준 약 16건). 부족하면 콘솔에서 충전
- 테스트는 `.env` 와 상관없이 가짜 문자 클라이언트를 쓴다 (`config/settings.py` 의 test 분기)
- 개발 DB(SQLite)의 기존 seed 신청은 차감 기능 이전에 만들어져 `StockIssue` 가 없다. 깨끗하게 보려면 `db.sqlite3` 삭제 → `migrate` → `seed_demo`

## 3. 남은 일

1. **서버 갱신**(코드가 바뀌면): 서버에서 `BRANCH=main bash /srv/mwt/deploy/update.sh`
2. **동시성 재확인**(잠금 관련 코드를 바꿨다면): 서버에서 `cd /srv/mwt && .venv/bin/python manage.py test apps.travel.test_concurrency --noinput`
   - 정원 10명 동시 초과 없음 / 기념품 재고 음수 없음 / 취소와 3번째 신청이 겹쳐도 확정된 신청은 취소되지 않음 / 같은 문자 동시 재발송은 1번만
3. (선택) 하루 한 번 `manage.py complete_trips` (cron·systemd timer). 안 해도 고객이 이력·금액을 볼 때 자동 반영됨
4. **발표(11/24) 후 AWS 정리**: 인스턴스 종료, 탄력적 IP 해제, `mwt-sg`·`mwt-key` 삭제. 크레딧(약 $140) 소진·만료 후에는 카드로 청구됨
