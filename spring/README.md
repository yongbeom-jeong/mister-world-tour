# 미스터 월드 투어: Spring Boot 이식판

Django 판(저장소 루트)과 같은 기능을 Spring Boot 4.1 + Thymeleaf 로 옮기는 중이다. Java 21 필요.

## 실행 (spring/ 폴더에서)
- 실행: `mvnw.cmd spring-boot:run` → http://localhost:8080 (`PORT` 환경변수로 바꿀 수 있음)
- 데모 데이터와 함께 실행: `mvnw.cmd spring-boot:run -Dspring-boot.run.arguments=--seed-demo` (여러 번 해도 중복 없음, 비밀번호 `demo1234!`)
- 테스트: `mvnw.cmd test`
- 배포 서버: `--spring.profiles.active=prod` (PostgreSQL, `.env`의 `POSTGRES_*`). 설치·갱신은 `deploy/DEPLOY.md`

개발 PC 는 H2 파일 DB(`spring/data/`)를 쓴다. 표는 Flyway(`src/main/resources/db/migration`)로만 만든다.
CSS·JS·사진은 저장소 루트 `static/` 을 빌드 때 함께 묶는다 (사본 없음).

## Django → Spring 대응
| Django | Spring |
|---|---|
| `apps/<앱>/` | `com.misterworldtour.<앱>` (members, travel, souvenir, speech, sms) + 공통 `common` |
| `models.py` + migrations | JPA 엔티티 + Flyway `V*.sql` |
| `services.py` | `*Service` (`@Service`). 컨트롤러는 Repository 를 직접 쓰지 않는다 |
| `@customer_required` / `@staff_role_required` | `@CustomerRequired` / `@StaffRoleRequired` |
| `messages` | `common.Messages` (`flash` / `now`) |
| context processor | `common.LayoutModelInterceptor` (currentMember, siteThemes, showHistoryPopup …) |
| `base.html` 의 block | `layout.html` 의 `page(title, pageHead, main, scripts)` 조각 |
| `.env` | 그대로 읽는다 (`spring/.env` 또는 저장소 루트 `.env`) |

URL 은 Django 판과 같게 둔다 (끝의 `/` 포함).

## 이식 현황
- [x] 뼈대: 공통 레이아웃, 홈(테마·이용 안내), 사진 출처, 403·404 화면
- [x] 회원(UC1 가입, UC2 로그인·로그아웃, 역할 검사)
- [x] 상품 조회 (목록·상세, 홈의 '곧 출발하는 여행', 직원 통계), 데모 데이터(`--seed-demo`)
- [x] 구성·가격·신청·출발 판정, 출발 확정 시 기념품 차감 (기념품 표·차감 로직 포함)
- [x] 기념품 직원 화면: 입고(준비→등록, 재시도 안전), 품목 등록·수정·삭제(내역 있으면 숨김), 차감 내역, 직원 홈 재고
- [x] 문자: 출발 확정 문자(재시도·시간 한도·결과 불명), 발송 기록·직원 재발송, 솔라피, 시험 발송(`--send-test-sms=번호`)
- [x] 음성·문장 검색, 신청 취소, 완료 이력(이력 팝업·단골 판정 전 반영, `--complete-trips`), 직원 상품 등록·관리(사진 업로드)
- [x] 배포: `spring/deploy/` (Java 21 · jar + systemd `mwt-spring` · Nginx · HTTPS · PostgreSQL DB `mwt_spring`). 안내는 `spring/deploy/DEPLOY.md`
- [ ] 아직 옮기지 않음: 평가 항목 측정 명령(`measure_evaluation`)
