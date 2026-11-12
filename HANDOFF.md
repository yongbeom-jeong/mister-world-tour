# 작업 인계 (HANDOFF)

마지막 갱신: 2026-11-12. 다음 세션은 이 파일 → `AGENTS.md` → `README.md` 순서로 읽고 시작한다.
일정: **최종 발표·시연 2026-11-24**. 지금은 **구현과 배포만** 다룬다 (문서·측정·시연 준비는 팀원이 마지막에 따로 처리).

## 1. 현재 상태

- **구현 완료.** 남은 일은 **AWS 서버 배포와 서버(PostgreSQL)에서의 동시성 확인**, 그리고 팀원이 할 문서 수정
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

## 3. 남은 일: 배포

1. **AWS 서버 배포·갱신** (`deploy/DEPLOY.md`)
   - 서버 가동 여부부터 확인 (저장소로는 알 수 없음). 이 PC에는 서버 SSH 키·접속 설정이 없음 → 서버 IP·계정·`.pem` 위치 필요
   - 갱신: `BRANCH=main bash /srv/mwt/deploy/update.sh`
     - 이번에 추가된 마이그레이션: `travel.0005`·`souvenir.0003`(11/11), `travel.0006`(취소), `sms.0001`(문자 기록), `souvenir.0004`(기념품 숨김), `members.0002`·`souvenir.0005`(대소문자 무시 고유 제약)
     - `members.0002`·`souvenir.0005` 는 서버 DB 에 대소문자만 다른 아이디·기념품 이름이 이미 있으면 실패한다. 실패하면 중복을 먼저 정리
   - 서버 `/srv/mwt/.env` 에 솔라피 4줄 + `SMS_ALLOWED_NUMBERS` 추가 → `sudo systemctl restart mwt`
   - 솔라피 콘솔에서 API Key 허용 IP에 **서버 공인 IP**(탄력적 IP 권장) 또는 AWS 대역 추가
   - 서버에서 `send_test_sms` 로 실제 수신 확인
   - 음성 인식은 HTTPS 필수: `setup.sh` 가 Let's Encrypt 인증서를 발급(보안 그룹 80·443 개방 필요). 실패 시 "문장으로 입력" 칸 사용
   - (선택) 하루 한 번 `manage.py complete_trips` (cron·systemd timer). 안 해도 고객이 이력·금액을 볼 때 자동 반영됨
2. **PostgreSQL 서버에서 동시성 확인**: SQLite 는 `select_for_update` 를 무시하므로 서버에서만 의미 있음
   - 같은 단체 상품에 동시 신청 → 정원 10명 초과 없음, 기념품 재고 음수 없음
   - 신청 취소와 3명째 신청이 동시에 → 확정된 신청이 취소되지 않음
   - 같은 문자 기록을 두 직원이 동시에 재발송 → 한 번만 발송
