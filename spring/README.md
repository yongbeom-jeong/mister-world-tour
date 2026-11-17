# 미스터 월드 투어: Spring Boot 이식판

Django 판(저장소 루트)과 같은 기능을 Spring Boot 4.1 + Thymeleaf 로 옮기는 중이다. Java 21 필요.

## 실행 (spring/ 폴더에서)
- 실행: `mvnw.cmd spring-boot:run` → http://localhost:8080 (`PORT` 환경변수로 바꿀 수 있음)
- 테스트: `mvnw.cmd test`
- 배포 서버: `--spring.profiles.active=prod` (PostgreSQL, `.env`의 `POSTGRES_*`)

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
- [ ] 상품 조회 (홈의 '곧 출발하는 여행', 직원 통계 포함)
- [ ] 구성·가격·신청·출발 판정
- [ ] 기념품, 문자, 음성, 취소·완료 이력, 직원 화면, seed_demo, 배포
