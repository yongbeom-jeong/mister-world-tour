# AGENTS.md

AI 코딩 도구(Claude Code, Codex 등) 공용 프로젝트 안내. 도구별 파일(CLAUDE.md 등)은 이 파일만 불러온다.

미스터 월드 투어: 테마 여행 상품 판매·기념품 재고 관리 웹 시스템 (소프트웨어공학 팀 프로젝트, 3인).
Django 5.2 + Django Template + Bootstrap 5, 운영 DB는 PostgreSQL. 최종 발표 2026-11-24.

## 명령 (Windows, 저장소 루트에서)
- 가상환경 Python: `.venv/Scripts/python` (전역 pip 설치 금지)
- 테스트: `.venv/Scripts/python manage.py test --noinput` (테스트 중엔 빠른 비밀번호 해시 사용, 1초 내외)
- 검사: `.venv/Scripts/python manage.py check`
- 모델 변경 시: `makemigrations` 후 `migrate`, 생성된 마이그레이션 파일도 함께 커밋
- 패키지 추가 시 `requirements.txt` 갱신
- 배포: `deploy/DEPLOY.md` (서버 `/srv/mwt`, systemd 서비스 `mwt`, 설치 `deploy/setup.sh`, 갱신 `deploy/update.sh`). `deploy/`·`*.sh`는 LF 줄바꿈 유지
- DB: 개발 PC는 SQLite, 배포 서버(AWS)만 PostgreSQL 18 (`.env`의 `POSTGRES_DB` 유무로 전환). 테스트는 `--noinput` 권장
  - SQLite는 `select_for_update` 잠금을 무시하므로, 동시성(정원·재고) 로직은 배포 서버의 PostgreSQL에서 다시 확인
- 데모 데이터: `.venv/Scripts/python manage.py seed_demo` (여러 번 실행해도 중복 없음, 비밀번호 `demo1234!`)
  - `staff1` 직원 / `regular1` 단골 고객(3년 내 완료 3회) / `customer2` 비단골(3년 내 2회) / `newbie` 이력 없음

## 구조와 설계 대응
설계 산출물(docs/설계산출물, 원고는 저장소 밖 ../output/설계산출물/*.md)의 서브시스템을 앱으로 나눈다.
- `apps/members` ↔ Member Management. `Member`가 AUTH_USER_MODEL (role: CUSTOMER/STAFF). 직원 여부는 `role`로 판단하고 Django의 `is_staff`(관리자 권한)와 혼동하지 않는다.
- `apps/travel` ↔ Travel Management, `apps/souvenir` ↔ Souvenir Management
  - travel: `services.py`(조회·직원 상품 관리), `booking.py`(고객 구성·검토·신청·출발 판정), `configuration.py`(구성 규칙), `pricing.py`(가격·할인)
  - 뷰: `views.py`(직원·이력), `customer_views.py`(고객 상품·신청)
- `apps/speech` ↔ Speech Recognition Integration, `apps/sms` ↔ SMS Notification
- Data Access 서브시스템은 별도 앱이 아니라 각 앱의 `models.py`(Django ORM)로 구현한다.
- UI 서브시스템은 `templates/` + 각 앱의 views 로 구현한다.
- 앱 간 호출은 상대 앱의 서비스 함수(예: `apps/travel/services.py`)를 통해 하고, 다른 앱의 모델을 직접 수정하지 않는다.

## 확정 업무 정책 (설계에서 확정, 임의 변경 금지)
- 장바구니 없음: 선택한 상품·구성을 바로 신청 화면으로 전달
- 허니문: 신청 고객 1명+동반 1명, 정확히 2인 신청마다 독립 출발 확정 후 해당 고객에게 문자
- 효도·골프·트레킹: 상품 전체 정원 10명, 실제 여행자 합계 3명 이상이면 출발, 정원 안에서 추가 신청 허용
- 할인: 최근 3년 내 완료 여행 3회 이상인 신청 고객은 전체 신청 금액 10% 할인
- 기념품: 테마에 연결(상품별 연결 없음). 신청 검토·등록 시 필요 수량 검사, **출발 확정 시 자동 차감** (2026-11-11 팀 결정으로 설계의 '차감은 범위 밖'을 변경)
  - 테마에 품목이 여러 개면 품목마다 여행자 1인당 1개. 허니문은 신청 확정 때, 그 외는 상품 확정 순간 그때까지의 신청 전부 + 이후 추가 신청마다 차감
  - 확정에 필요한 수량이 모자라면 신청 자체를 거부(트랜잭션 취소). 차감 내역은 `StockIssue`((품목, 신청)마다 1행, 이중 차감 방지)
  - 테마에 기념품이 하나도 없으면 그 테마 상품은 신청 불가
- 문자: 최초 발송 후 최대 3회 재시도. 문자 실패가 저장된 신청·출발 상태를 되돌리지 않음

## 임시 결정 (설계 미정 → 구현에서 임의로 정함, 바꿀 때 이 목록도 갱신)
- 직원 가입: `STAFF_SIGNUP_CODE`(.env, 기본 `MWT-STAFF-2026`)를 입력해야 직원 유형으로 가입
- 아이디: 영문·숫자·밑줄 4~20자, 대소문자 무시 중복 검사. 연락처: 휴대전화 형식, `010-1234-5678`로 정규화
- 여행 기간: 일 수(`travel_days`). 상품에 여행지(`destination`)·소개(`description`) 필드 추가
- 옵션 1인 금액: `apps/travel/pricing.py`의 `OPTION_PRICE_TABLE`. 기본 구성 대비 차액 반영 (올리면 +, 내리면 −). 1인 가격 하한은 기본 가격의 50%
  - 호텔 3/4/5등급 0/15만/35만, 식사 도시락/현지식/고급 0/8만/20만, 리무진 +15만
- 할인액: 원 미만 버림. 여행자 입력 항목: 성명·연락처
- 이력 팝업: 고객 로그인 직후 홈에서 1회 자동 표시, 홈의 "이력 보기"로 재조회 (`/travel/history/` HTML 조각)
- 상품 입력 범위(`apps/travel/forms.py`): 1인 가격 1만~5천만 원, 기간 1~30일, 출발일은 내일 이후, 여행지 필수
- 상품 수정 시 기본 구성·기념품·출발 상태를 테마·등급으로 다시 결정 (신청이 없는 상품만 수정 가능하므로 안전)
- 기념품 등록(`/souvenir/staff/souvenirs/new/`): 이름(50자, 대소문자 무시 중복 금지)+테마, 재고 0으로 등록 후 입고 화면으로 이동
- 기념품 입고: 1회 최대 10,000개. 준비 문맥(요청 키별)은 세션 `stock_receipt_pending`에 최대 20개 보관
  - 화면은 준비(prepare) → 등록(record)을 JS로 연속 호출. 응답 불명이면 같은 키로만 "다시 확인" (재고 이중 증가 없음)
- 교통: 허니문은 2인 차량, 나머지는 10인 승합 차량만 가능. 공통 옵션 '리무진 픽업'(+15만) 추가
- 음료(1인 1세트): 와인 +5만, 수제맥주 +2만, 샴페인 +6만, 스페셜티 커피 +1만(원문 "샴페인, 커피 추가"). 도시락에는 추가 불가, 고급 식사엔 샴페인 기본 포함(0원)
- 여행자 입력: 첫 행은 신청 고객 본인(계정 성명·연락처), 동반자는 성명 필수·연락처 선택. 기념품 재고는 검사만 하고 차감하지 않음
- 검토 내용(요청 키별)은 세션 `application_reviews`에 최대 10개, 선택 상품·구성은 `application_draft`
- 문자: `.env`의 `SMS_CLIENT`(기본 `ConsoleSMSClient`, runserver 창에 로그만). 시연용 `FlakyDemoSMSClient`(번호마다 2번 실패 후 성공)
  - 실제 발송은 솔라피 `apps/sms/solapi.py`의 `SolapiSMSClient` (`SOLAPI_API_KEY`·`SOLAPI_API_SECRET`·`SOLAPI_SENDER`). 시험 발송 `manage.py send_test_sms 번호`
  - 출발 확정 문구는 단문(90byte, 한글 2byte) 안: `[미스터월드투어] 홍길동님, 상품명 12월 1일 출발 확정되었습니다.` 넘치면 이름 생략 → 상품명 줄임
  - `SMS_ALLOWED_NUMBERS`(쉼표 구분)가 있으면 그 번호로만 실제 발송, 나머지는 `SMSRejected`(재시도 없이 실패)
  - 클라이언트는 `send_sms(contact, message)`만 가지면 교체 가능. 실패는 `SMSSendError`(재시도), 결과 불명은 `SMSUnknownResult`(재시도 안 함)
  - 솔라피 HTTP 5xx·시간 초과는 결과 불명(중복 문자 방지), 4xx·접수 거부는 실패. 테스트는 `.env`와 무관하게 `ConsoleSMSClient` 고정
- 음성: `.env`의 `SPEECH_CLIENT`(기본 `BrowserTranscriptClient` = 브라우저 Web Speech API 인식 문장 사용, Chrome·Edge)
  - 해석 별칭(`apps/speech/services.py`): 신혼→허니문, 부모님→효도, 등산→트레킹 등. 상품은 고유 단어(여행지 등)나 순서("두 번째", "3번")
  - 상품 목록 화면에 "문장으로 입력" 칸이 있어 마이크 없이도 시험 가능
- seed_demo 신청: customer2가 다낭 골프 2명(1명 더 오면 확정), 제주 효도 8명(확정, 남은 2자리)

## 화면 디자인
- 고급 여행사 콘셉트: 네이비(`--mwt-navy`) + 골드(`--mwt-gold`), 본문 Pretendard, 제목 Noto Serif KR. 공통 스타일은 `static/css/mwt.css` 하나에서 관리
- 공통 부품: `.mwt-page-head`(내부 페이지 머리), `.mwt-hero`(사진 배너), `.mwt-card`/`.mwt-card-body`, `.product-card`(`templates/travel/customer/_product_card.html`), `.tag`, `.chip-group`, `.option-tile`, `.stepper`, `.btn-gold`
- PC 화면: `.home-hero`는 400px, 상단 메뉴는 72px. 상품 목록은 `.catalog-head`/`.catalog-controls`, 상세 옵션은 `.configuration-grid`에서 관리. `.booking-bar`는 PC에서 하단 고정이며 `.product-detail-page`의 하단 여백으로 콘텐츠 가림을 방지한다. 가격·구성·신청 버튼의 id는 각각 한 번만 사용한다. 직원용 제목은 `body.mwt-staff`에서 간결하게 표시한다.
- 장식은 절제한다: 사진과 제목을 분리하고 상품은 그림자 없는 목록으로 표시한다. 영문 장식 소제목은 사용하지 않으며, 테마·등급은 일반 텍스트, 출발 상태는 의미 있는 배지로 구분한다. 금색은 브랜드와 주요 행동에 집중하고 일반 버튼은 6px 모서리를 사용한다.
- 템플릿 도구 `{% load mwt %}`: `product|product_photo[:"sm"]`, `값|won`, `테마코드|theme_info:"en|icon|tagline|photo"`, `{% photo "이름" ["sm"] %}`, `dict|get_item:키`
- 사진: 기본 사진은 `static/img/photos/`(1600px, `sm/`은 800px), 출처는 `credits.json` → `/credits/` 페이지. CC BY·BY-SA 라 출처 표시 필수, 사진을 바꾸면 credits.json 도 갱신
- 상품 사진 업로드: `TravelProduct.image`(media/products/…), 서버에서 최대 1600px JPEG 로 재저장(`apps/travel/images.py`). 없으면 테마 기본 사진
- JS 가 쓰는 id(`id_theme`, `config-input`, `review-panel` 등)는 화면마다 하나만. 같은 화면의 다른 폼은 `auto_id` 를 바꿔 충돌을 피한다 (예: 목록 필터 `filter_%s`)
- 브라우저로 확인할 때 휴대폰 폭은 같은 사이트 iframe(390px)으로 미리보기 가능 (DEBUG 에서만 SAMEORIGIN 허용)

## 규칙
- 화면 문구·주석은 한국어. 코드 식별자는 영어.
- 비밀값(API 키, DB 비밀번호)은 `.env`에만 두고 `settings.py`에서 `os.getenv`로 읽는다.
- `docs/`의 .doc/.hwpx/.mdj 파일은 코드 작업 중 수정하지 않는다.
- 외부 음성인식 API는 업체 미정, 문자는 솔라피: 인터페이스 뒤에 두고 개발·테스트에서는 가짜(mock) 구현을 쓴다.
- 브랜치를 만들어 작업하고 PR로 main에 병합한다.
- 역할 검사는 `apps/members/decorators.py`의 `customer_required` / `staff_role_required`를 뷰에 붙여 서버에서 한다.
