# AGENTS.md

AI 코딩 도구(Claude Code, Codex 등) 공용 프로젝트 안내. 도구별 파일(CLAUDE.md 등)은 이 파일만 불러온다.

미스터 월드 투어: 테마 여행 상품 판매·기념품 재고 관리 웹 시스템 (소프트웨어공학 팀 프로젝트, 3인).
작업 인계·남은 일은 `HANDOFF.md` 를 먼저 확인한다.
**이 브랜치(`spring`)는 Java Spring Boot 판**이다 (Spring Boot 4.1, Java 21, Thymeleaf, Spring Security, JPA, Flyway, Bootstrap 5).
Django 판은 `main` 브랜치에 따로 있으며 두 브랜치는 합치지 않는다. 최종 발표 2026-11-24.

## 명령 (Windows, 저장소 루트에서)
- 실행: `.\mvnw.cmd spring-boot:run` (http://localhost:8080, 포트는 `PORT` 환경변수)
- 데모 데이터와 함께: `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--seed-demo"` (여러 번 실행해도 중복 없음, 비밀번호 `demo1234!`)
  - `staff1` 직원 / `regular1` 단골 고객(3년 내 완료 3회) / `customer2` 비단골(3년 내 2회) / `newbie` 이력 없음
- 테스트: `.\mvnw.cmd test` (메모리 H2, `.env` 와 무관. 1분 내외)
- 완료 이력 일괄: `--complete-trips`, 시험 문자: `--send-test-sms=010-1234-5678` (위와 같이 `spring-boot.run.arguments` 로)
- 엔티티 변경 시 `src/main/resources/db/migration` 에 새 `V번호__설명.sql` 추가 (이미 올린 SQL 은 고치지 않음). DB 열 이름은 그대로 두고 Java 필드만 설계 이름을 쓴다
- DB: 개발 PC 는 H2 파일 DB(`data/`), 배포 서버만 PostgreSQL 18 (`--spring.profiles.active=prod`, `.env` 의 `POSTGRES_*`)
  - H2 도 행 잠금을 지키므로 동시성(정원·재고) 테스트 `BookingConcurrencyTest` 가 개발 PC 에서도 돈다
- 배포: `deploy/DEPLOY.md` (서버 `/srv/mwt-spring`, systemd 서비스 `mwt-spring`, 설치 `deploy/setup.sh`, 갱신 `deploy/update.sh`). `deploy/`·`*.sh`·`mvnw` 는 LF 줄바꿈 유지
- 아직 옮기지 않은 것: 평가 항목 측정 명령(Django 판 `measure_evaluation`)

## 설계와 구현의 대응 (가장 중요)
문서(계획서·요구분석서·설계서, `docs/`)에 맞춰 구현한다. 패키지·클래스·메소드 이름은 설계서 클래스도의 이름을 그대로 쓴다
(설계의 PascalCase 메소드는 Java 관례대로 camelCase: `SignUp` → `signUp`). **문서대로 하면 동작에 문제가 생기는 경우에만 구현을 바르게 하고
문서를 고친다** (문서·구현 대조는 팀이 따로 한다).

| 패키지 | 서브시스템 (설계 장) | 클래스 |
|---|---|---|
| `ui` | User Interface (3장) | `MainView`, `MemberView`, `TravelHistoryView`, `TravelProductView`, `TravelApplicationView`, `TourProductAdminView`, `SouvenirStockView` / 설계 이후 추가 화면 `SouvenirAdminView`, `SMSLogView` |
| `members` | Member Management (4장) | `MemberManagementAPI`, `MemberManager`(signUp·logIn), `Member`(checkPassword·getMemberId·getRole), `LoginSession` |
| `travel` | Travel Management (5장) | `TravelManagementAPI`, `TravelManager`, `TravelProduct`(validateProduct·validateApplicationCapacity·updateDepartureStatus), `TravelConfiguration`(applyChanges·validateConfiguration), `TravelPriceCalculator`(calculateUnitPrice·isRegularCustomer·calculateApplicationPrice), `TravelApplication`(validateTravelers·getTravelerCount·confirmHoneymoonDeparture), `TravelHistory`(isCompletedWithinThreeYears) / 추가 `SouvenirStockAPI`(기념품 재고 검사·출발 차감, `SouvenirManager` 가 구현) |
| `souvenir` | Souvenir Management (6장) | `SouvenirManagementAPI`, `SouvenirManager`(loadSouvenirs·prepareStockReceipt·recordSouvenirStockReceipt), `Souvenir`, `StockReceipt`(validateReceipt·matchesRequest) / 추가 `StockIssue` |
| `dataaccess` | Data Access (7장) | `DataAccessAPI`(아래 저장 인터페이스 4개를 묶음), `DataAccessManager`, `MemberRepository`, `TravelRepository`(runTravelTransaction 등), `SouvenirRepository` / 추가 `SMSMessageRepository` |
| `speech` | Speech Recognition Integration (8장) | `SpeechRecognitionAPI`, `SpeechRecognitionManager`(recognizeSelection), `SpeechServiceClient`(recognize), `VoiceSelectionInterpreter`(interpretSelection) |
| `sms` | SMS Notification (9장) | `SMSNotificationAPI`, `SMSNotificationManager`(sendDepartureConfirmation), `SMSServiceClient`(sendSMS), `SMSMessage`(recordResult·canRetry, 상태 Pending/Success/Failure/Unknown) |
| `common` | (설정) | `SecurityConfig`, `WebConfig`, `MwtProperties` |

- 호출 방향: `ui` 의 View → 업무 서브시스템의 `*API`/`*Manager` → 자기 저장 인터페이스. View 와 업무 클래스는 Repository·EntityManager 를 직접 쓰지 않는다
- 저장 인터페이스는 쓰는 쪽이 가진다 (의존성 역전, 설계 7장과 다름): `members.MemberDataAccessAPI`, `travel.TravelDataAccessAPI`, `souvenir.SouvenirDataAccessAPI`, `sms.SMSMessageDataAccessAPI`.
  `dataaccess.DataAccessAPI` 는 이 넷을 묶고 `DataAccessManager` 가 구현한다. 의존은 dataaccess → 업무 한 방향뿐이며, 업무·ui·common 이 `dataaccess` 를 참조하면 `PackageDependencyTest` 가 실패한다
- 패키지 사이 순환 의존 금지 (`PackageDependencyTest`). 같은 방법으로 travel 이 souvenir 를 부르지 않고 `travel.SouvenirStockAPI` 를 쓴다 (souvenir → travel 한 방향).
  `common` 은 `ui` 를 참조하지 않는다: 로그인 성공·실패 처리(`ui.LoginHandlers`)·화면 인터셉터(`ui.LayoutModelInterceptor`)는 ui 의 빈이고, `SecurityConfig`·`WebConfig` 는 스프링 인터페이스 타입으로 주입받는다
- 다른 서브시스템은 그 서브시스템의 `*API` 로만 부른다. `*Manager` 를 직접 참조하면 `PackageDependencyTest` 가 실패한다 (데모 데이터 `demo` 는 예외).
  화면이 쓰는 조회·관리 기능, 다른 서브시스템이 쓰는 기능(연락처 조회, 문자 발송 준비 등)과 그 결과·예외 타입도 `*API` 에 둔다
- 다른 서브시스템의 엔티티는 id 로 참조하고 직접 수정하지 않는다 (예: 신청은 회원을 `memberId` 로만)
- 트랜잭션: 업무 메소드가 경계를 정하고 `DataAccessManager` 는 참여한다. 같은 상품을 잠그고 묶을 때는 `TravelDataAccessAPI.runTravelTransaction(productId, work)`
- 로그인한 회원은 `LoginSession` (Spring Security 인증 주체). 설계처럼 회원 번호를 인자로 받지 않는 API 메소드(viewPreviousTravelHistory, reviewApplication, prepareStockReceipt 등)는 `LoginSession.current()` 로 확인한다. 테스트·데모용으로 회원 번호를 받는 같은 이름의 메소드도 둔다
- 로그인 처리: Spring Security 폼 로그인 → `MemberAuthenticationProvider` → `MemberManager.logIn` → `LoginSession`
- 역할 검사는 `members` 의 `@CustomerRequired` / `@StaffRoleRequired` 를 View 에 붙여 서버에서 한다. 직원 여부는 `Role` 로만 판단

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
- 신청 취소(2026-11-12 결정): 출발 확정 전에만 고객이 취소. 허니문은 신청과 함께 확정되므로 취소 불가. 신청 확인 화면에 "출발이 확정되면 신청을 취소할 수 없어요" 안내
  - 확정 전에는 기념품을 차감하지 않으므로 취소 시 되돌릴 재고가 없다
- 완료 여행 이력(2026-11-12 결정): 직원이 만들지 않는다. 출발 확정된 여행의 마지막 날이 지나면 자동으로 완료 이력이 된다

## 임시 결정 (설계 미정 → 구현에서 임의로 정함, 바꿀 때 이 목록도 갱신)
- 직원 가입: `STAFF_SIGNUP_CODE`(.env, 기본 `MWT-STAFF-2026`)를 입력해야 직원 유형으로 가입 (화면 `SignUpFormValidator` 에서 확인)
- 아이디: 영문·숫자·밑줄 4~20자, 대소문자 무시 중복 검사. 연락처: 휴대전화 형식, `010-1234-5678`로 정규화 (`MemberManager`)
- 비밀번호 규칙 4가지(`PasswordPolicy`): 8자 이상, 숫자만 금지, 흔한 비밀번호 금지, 아이디·이름과 비슷하면 금지
- 여행 기간: 일 수(`TravelProduct.travelPeriod`, DB 열 `travel_days`). 상품에 여행지(`destination`)·소개(`description`) 필드 추가
- 옵션 1인 금액: `TravelPriceCalculator.OPTION_PRICE_TABLE`. 기본 구성 대비 차액 반영 (올리면 +, 내리면 −). 1인 가격 하한은 기본 가격의 50%
  - 호텔 3/4/5등급 0/15만/35만, 식사 도시락/현지식/고급 0/8만/20만, 리무진 +15만
- 할인액: 원 미만 버림. 여행자 입력 항목: 성명·연락처
- 이력 팝업: 고객 로그인 직후 홈에서 1회 자동 표시, 홈의 "이력 보기"로 재조회 (`/travel/history/` HTML 조각, `TravelHistoryView`)
- 상품 입력 범위(`TravelProduct.validateProduct`): 1인 가격 1만~5천만 원, 기간 1~30일, 출발일은 내일 이후, 여행지 필수. 숫자·날짜 형식은 화면 `ProductForm`
- 상품 기본 구성(UC6 (3)): 테마·등급 기본값을 보여 주고 직원이 확인한 뒤 바꿀 수 있다. 호텔·식사는 등급 기본값 이상, 교통은 테마 허용값만, 비우면 기본값 (`TravelConfiguration.baseOptionChoices`·`validateConfiguration(theme, level)`)
- 상품 수정 시 기본 구성·출발 상태를 다시 결정 (신청이 없는 상품만 수정 가능하므로 안전)
- 허니문·효도는 그랜드 이상만(계획서 1.다): 상품 초기 등급과 함께 고객 옵션도 4등급 이상 호텔·현지식 이상 식사만
- 기념품 등록(`/souvenir/staff/souvenirs/new/`): 이름(50자, 대소문자 무시 중복 금지)+테마, 재고 0으로 등록 후 입고 화면으로 이동
- 기념품 입고: 1회 최대 10,000개. 입고 준비 정보(요청 키별)는 `SouvenirManager` 가 서버 메모리에 직원마다 최대 20개 보관 (서버를 다시 시작하면 준비부터 다시)
  - 입고 JSON 요청(목록·준비·등록)의 권한 없음·로그인 만료는 `SecurityConfig` 가 JSON 403 `PERMISSION_DENIED` 로 응답 (결과 상태 7가지 `StockReceiptStatus`)
  - 화면은 준비(prepare) → 등록(record)을 JS로 연속 호출. 응답 불명이면 같은 키로만 "다시 확인" (재고 이중 증가 없음)
- 교통: 허니문은 2인 차량, 나머지는 10인 승합 차량만 가능. 공통 옵션 '리무진 픽업'(+15만) 추가
- 음료(1인 1세트): 와인 +5만, 수제맥주 +2만, 샴페인 +6만, 스페셜티 커피 +1만(원문 "샴페인, 커피 추가"). 도시락에는 추가 불가, 고급 식사엔 샴페인 기본 포함(0원)
- 여행자 입력: 첫 행은 신청 고객 본인(계정 성명·연락처), 동반자는 성명 필수·연락처 선택. 기념품 재고는 검토 때 검사만 하고, 차감은 출발 확정 때 (위 확정 정책)
- 검토 결과(요청 키별)는 세션 `application_reviews`에 최대 10개, 선택 상품·구성은 `application_draft` (`TravelApplicationView`)
  - `submitTravelApplication` 은 설계 매개변수(상품·구성·여행자·요청 키)에 더해 검토 때 보여 준 금액을 받아, 바뀌었으면 재검토를 요구한다
  - 같은 요청 키 재제출은 고객·상품·구성·여행자가 모두 같을 때만 기존 신청으로 안내, 다르면 거부
  - 신청 등록 중 DB 오류: 같은 키로 저장 여부를 다시 조회해 저장됨(상세로 이동)/저장 안 됨(같은 키로 재제출)/확인 불가('내 신청' 확인 안내)로 나눠 안내
- 문자: `.env`의 `SMS_CLIENT` = `Console`(기본, 실행 창에 로그만) / `FlakyDemo`(번호마다 2번 실패 후 성공) / `Solapi`(실제 발송, `SolapiSMSClient`, `SOLAPI_API_KEY`·`SOLAPI_API_SECRET`·`SOLAPI_SENDER`)
  - 출발 확정 문구는 단문(90byte, 한글 2byte) 안: `[미스터월드투어] 홍길동님, 상품명 12월 1일 출발 확정되었습니다.` 넘치면 이름 생략 → 상품명 줄임
  - `SMS_ALLOWED_NUMBERS`(쉼표 구분)가 있으면 그 번호로만 실제 발송, 나머지는 보내기 전 거부(재시도 없이 실패)
  - 클라이언트는 `SMSServiceClient.sendSMS(contact, message)`만 가지면 교체 가능. 실패는 `SendException`(재시도), 결과 불명은 `UnknownResultException`(재시도 안 함)
  - 솔라피 HTTP 5xx·시간 초과는 결과 불명(중복 문자 방지), 4xx·접수 거부는 실패. 테스트는 `.env`와 무관하게 Console 고정
  - 신청 1건의 문자 묶음 전체에 시간 한도 `SMS_BATCH_TIME_LIMIT`(기본 40초). 한도 안에 못 끝낼 시도는 하지 않고 실패로 표시
- 음성: `SpeechServiceClient` 기본 구현 `BrowserTranscriptClient` = 브라우저 Web Speech API 인식 문장 사용 (Chrome·Edge)
  - 브라우저에서 끝난 인식 실패(무음·마이크 권한 등)도 `/speech/failure/`로 보내 서버 로그에 기록 (UC4 4a·7a)
  - 해석 별칭(`VoiceSelectionInterpreter`): 신혼→허니문, 부모님→효도, 등산→트레킹 등. 상품은 고유 단어(여행지 등)나 순서("두 번째", "3번")
  - 상품 목록 화면의 음성 검색창은 [마이크][문장 입력칸][찾기] 한 줄. 마이크 없이 문장으로도 시험 가능. 듣는 중 마이크를 다시 누르면 꺼지고, 사용자가 끈 것은 실패로 기록하지 않음
- 신청 취소는 `cancelled_at` 기록(행 삭제 아님). 취소한 신청은 정원·출발 판정·문자·차감·홈 통계에서 제외, 출발일이 지난 신청도 취소 불가
- 완료 이력: 여행 마지막 날 = 출발일 + 기간 − 1, 그다음 날부터 완료. 신청 고객 계정에 상품당 이력 1건(`completedTravelId=product-<id>`, 같은 상품 신청이 여럿이어도 다녀온 여행 1회), 가격은 그 신청 금액 합계
  - 이력 조회·신청 금액 계산 직전에 그 고객 것을 반영(`TravelManager.completeFinishedTrips`). 일괄은 `--complete-trips`
- 문자 발송 기록은 `SMSMessage`(표 `sms_log`, 수신자 1명당 1행, 고객·상품은 id·이름 값으로 보관). 직원 화면 `/sms/staff/logs/` (`SMSLogView`)
  - 기록은 신청 저장과 같은 트랜잭션에서 Pending 으로 먼저 만들고(`prepareDepartureConfirmation`, JDBC 저장점), 저장 뒤 발송(`sendDepartureConfirmation(plan)`). 보내기 전 서버가 멈춰도 기록이 남음
  - 재발송은 실패·결과 불명·5분 넘게 멈춘 Pending 만. 결과 불명·멈춤은 중복 수신 확인 체크 필요. 같은 행에 시도 횟수 누적, 재발송마다 새 회차로 최대 4회
- 기념품 관리 `/souvenir/staff/souvenirs/` (`SouvenirAdminView`): 이름·테마 수정, 삭제, 차감 내역(`issues/`). 재고는 입고·차감으로만 변경
  - 삭제: 입고·차감 내역이 없으면 실제 삭제, 있으면 `is_active=false`로 숨김(신청 검사·차감·입고에서 제외, 같은 이름 재등록 불가)
- 데모 신청: customer2가 다낭 골프 2명(1명 더 오면 확정), 제주 효도 8명(확정, 남은 2자리)

## 화면 디자인
- 2026-11-13 리뉴얼: 에어비앤비식 배치를 참고한 깔끔한 화면 (로고·색·아이콘은 가져오지 않음). 공통 스타일은 `src/main/resources/static/css/mwt.css` 하나에서 관리
- 색: 흰 바탕, 글자 `--mwt-ink`(#222), 보조 `--mwt-muted`, 선 `--mwt-line`. 강조색은 딥 틸 `--mwt-accent`(#0F766E) 하나만, 주요 버튼(`.btn-primary`)·선택 상태·현재 단계에만 쓴다
  - 상태 배지만 의미 색: `.tag-success`(출발 확정·성공), `.tag-warning`(확인 필요), `.tag-danger`(취소·실패), `.tag-muted`(미확정). 그 외 `.tag-accent`, `.tag-dark`(본인)
  - 버튼: `.btn-primary`(주요, 강조색) / `.btn-dark`(보조) / `.btn-outline-primary`(일반, 회색 테두리). 모서리 8px, 카드·사진 12px
- 글꼴: Pretendard 하나 (제목은 굵기로 구분, 세리프 없음). 본문 `word-break: keep-all` 로 한글 단어 단위 줄바꿈
- 공통 부품: `.mwt-page-head`, `.mwt-back`, `.mwt-card`/`.mwt-card-body`, `.product-card`(`templates/travel/customer/_product_card.html`), `.tag`, `.chip-group`, `.option-tile`, `.stepper`, `.empty-state`, `.app-card`
- 홈: 사진 배너 없이 가운데 검색 막대(`.search-pill`, 테마·등급 → 상품 목록 GET). 테마 4개 + 곧 출발하는 여행 8개(4열)
  - 테마·등급은 Bootstrap dropdown 선택 패널(`.search-menu`, 라디오 버튼). 등급 요약은 `ui.Display.levels()` 가 `TourLevel` 기본값에서 만든다
- 푸터: 바로가기 열(고객·비로그인: 테마·내 여행 / 직원: 직원 메뉴) + 이용 안내(실제 업무 규칙만) + 맨 아래 `© 2026 미스터 월드 투어`·사진 출처. 로고·소개 문구·가짜 회사 정보는 넣지 않는다 (사진 출처 링크는 라이선스상 필수)
- 상품 목록: 음성·문장 검색창(`.voice-search`) → `.catalog-toolbar`(테마·등급 칩) → 카드 PC 4열·휴대폰 1열. 휴대폰에서 칩 줄은 가로로 밀어 본다
- 상품 상세: 제목 → 큰 사진 → 왼쪽 소개·포함·구성(`.detail-section`, `.configuration-grid`), 오른쪽 `.booking-bar` 는 PC 에서 따라오는 예약 카드, 992px 미만에서는 하단 고정 바 (같은 요소라 가격·구성·신청 버튼 id 는 한 번만)
- 상단 메뉴(세 칸, sticky, PC 80px): 왼쪽 로고 · 가운데 역할별 아이콘 탭(`.mwt-tab`) · 오른쪽 계정 메뉴(`.mwt-menu-btn`). 휴대폰은 탭이 둘째 줄. 따라오는 패널은 top 96px
- 홈·상품 목록은 제목·검색창·필터 칩을 가운데 정렬. Bootstrap 행 간격(`g-5`)은 휴대폰에서 가로 넘침을 만들므로 `g-4 g-lg-5`처럼 쓴다
- 브라우저 기본 입력 UI 를 그대로 보이지 않는다 (`static/js/mwt-ui.js`, 모든 화면에서 로드)
  - `select.form-select` 는 디자인된 목록(버튼 + Bootstrap dropdown)이 된다. 값은 숨긴 원래 select 에 있어 폼 전송은 그대로. 기본 select 가 필요하면 `data-native`
  - `input[type=date]` 는 flatpickr(한국어) 달력. flatpickr 를 불러온 화면에서만 (`_product_form.html`). 서버로 가는 값은 `yyyy-MM-dd`
  - 브라우저 검증 말풍선 대신 화면 안 안내: 폼에 `novalidate`, 필수 검사는 서버. 숫자 칸 위아래 화살표는 숨김
- 템플릿 도구: `ui.ViewHelper` 를 `@mwt` 로 쓴다 (`${@mwt.won(값)}`, `${@mwt.theme(테마)}`, `${@mwt.date(날짜, 'M월 d일')}`). 상품 사진은 `product.photoUrl`·`photoSmUrl`
- 사진: 기본 사진은 `static/img/photos/`(1600px, `sm/`은 800px), 출처는 `credits.json` → `/credits/` 페이지. CC BY·BY-SA 라 출처 표시 필수, 사진을 바꾸면 credits.json 도 갱신
- 상품 사진 업로드: `TravelProduct.image`(media/products/…), 서버에서 최대 1600px JPEG 로 재저장(`ProductImages`). 없으면 테마 기본 사진
- JS 가 쓰는 id(`id_theme`, `config-input`, `review-panel` 등)는 화면마다 하나만. JS 가 받는 JSON 키(`id`, `name`, `stock_quantity` 등)는 엔티티 속성 이름과 별개이니 바꾸지 않는다

## 규칙
- 화면 문구·주석은 한국어. 코드 식별자는 영어 (설계서 이름 우선).
- 비밀값(API 키, DB 비밀번호)은 `.env`에만 두고 `application.yml` 에서 `${이름}` 으로 읽는다.
- `docs/`의 .doc/.hwpx/.mdj 파일은 코드 작업 중 수정하지 않는다.
- 외부 음성인식 API는 업체 미정, 문자는 솔라피: 인터페이스(`SpeechServiceClient`, `SMSServiceClient`) 뒤에 두고 개발·테스트에서는 가짜 구현을 쓴다.
- 브랜치를 만들어 작업하고 PR로 `spring` 에 병합한다 (`main` 은 Django 판).
