# 미스터 월드 투어 (Mister World Tour)

**2026년 2학기 소프트웨어공학 · 3인 팀 프로젝트**

테마 여행 상품의 **조회·신청·출발 확정**과 기념품 재고 관리를 구현하는 Java Spring Boot 웹 애플리케이션입니다.

> 이 브랜치(`spring`)는 Spring Boot 판입니다. 처음 만든 Django 판은 `main` 브랜치에 그대로 있습니다.
> 화면·URL·업무 규칙은 두 판이 같습니다.

## 프로젝트 소개

- **목표:** 허니문·효도·골프·트레킹 상품별 신청 규칙과 재고 조건을 하나의 서비스에서 일관되게 처리
- **기술:** Java 21, Spring Boot 4.1, Spring MVC + Thymeleaf, Spring Security, Spring Data JPA(EntityManager), Flyway,
  Bootstrap 5, H2(개발 PC), PostgreSQL 18(서버)
- **설계와 구현:** 패키지·클래스·메소드 이름을 설계 산출물(아키텍처도·클래스도·교류도)과 맞췄다 (아래 "구조")
- **배포 구성:** AWS EC2 / Ubuntu / Nginx / systemd(jar) / PostgreSQL 설치·갱신 스크립트
- **외부 연동:** 문자는 솔라피(설정에 따라 가짜 클라이언트로 전환), 음성은 브라우저 내장 음성인식

## 핵심 설계 문제

| 상황 | 결정·구현 방향 |
| --- | --- |
| 여행 유형마다 출발 확정 조건이 다름 | 허니문은 2인 신청 단위, 그 외 테마는 3인 이상 모집 시 출발 확정 |
| 신청·정원·재고를 함께 처리해야 함 | 상품 행을 잠근 한 트랜잭션(RunTravelTransaction)에서 정원·재고·금액을 다시 검사하고 저장 |
| 같은 입고 요청이 재전송될 수 있음 | 요청 키로 중복 입고 반영을 막는 흐름 구성 |
| 문자 발송 결과가 불분명할 수 있음 | 명확한 실패와 결과 불명을 구분하고, 결과 불명 시 자동 재발송하지 않음 |
| 구성원마다 요구사항 해석이 다를 수 있음 | UML 설계 산출물과 변경 기록을 기준으로 의사결정 공유 |

## 구현 및 검증 참고

- [요구사항·설계 자료](docs/)
- [신청·출발 확정](mwt-business/mwt-travel/src/main/java/com/misterworldtour/travel/TravelManager.java) · [신청 테스트](mwt-app/src/test/java/com/misterworldtour/travel/BookingServiceTest.java)
- [문자 발송](mwt-business/mwt-sms/src/main/java/com/misterworldtour/sms/SMSNotificationManager.java) · [문자 테스트](mwt-app/src/test/java/com/misterworldtour/sms/SMSDeliveryTest.java)
- [배포 가이드](deploy/DEPLOY.md)
- [변경 이력 및 리뷰](../../pulls)

**팀 프로젝트 참고:** 실제 개인별 기여 내용은 PR, 커밋, 팀별 업무 분담 기록을 통해 확인해야 합니다. 저장소에 포함된 전체 기능을 특정 개인이 단독 개발했다고 주장하지 않습니다.

---

## 처음 설정 (Windows PowerShell)

Java 21 이상(JDK)만 있으면 된다. Maven 은 저장소에 들어 있는 Maven Wrapper(`mvnw.cmd`)가 받아 쓴다.

```powershell
java -version                          # 21 이상인지 확인
Copy-Item .env.example .env            # 필요하면 값 수정 (STAFF_SIGNUP_CODE, 문자 설정 등)
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--seed-demo"   # http://localhost:8080
```

- `--seed-demo` 는 데모 계정·상품·재고·이력·신청을 넣는다. 여러 번 실행해도 중복되지 않는다 (비밀번호 `demo1234!`).
  - `staff1` 직원 / `regular1` 단골 고객(3년 내 완료 3회) / `customer2` 비단골 / `newbie` 이력 없음
- 표(테이블)는 시작할 때 Flyway 가 `mwt-persistence/mwt-dataaccess/src/main/resources/db/migration` 의 SQL 로 만든다.
- 포트를 바꾸려면 `PORT` 환경변수를 쓴다 (예: `$env:PORT=8081`).

## 자주 쓰는 명령 (저장소 루트에서)

| 작업 | 명령 |
|---|---|
| 개발 서버 | `.\mvnw.cmd spring-boot:run` |
| 데모 데이터와 함께 실행 | `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--seed-demo"` |
| 테스트 | `.\mvnw.cmd test` |
| jar 만들기 | `.\mvnw.cmd package` (`mwt-app/target/mwt.jar`) |
| 완료 여행 이력 일괄 정리 | `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--complete-trips"` |
| 시험 문자 1통 | `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--send-test-sms=010-1234-5678"` |

## 데이터베이스

| 환경 | DB | 설정 |
|---|---|---|
| 개발 PC | H2 파일 DB (`data/`, 설치 불필요) | 기본값. 처음 상태로 돌리려면 서버를 끄고 `data/` 를 지운다 |
| 배포 서버 (AWS) | PostgreSQL 18, DB `mwt_spring` | `--spring.profiles.active=prod` + `.env`의 `POSTGRES_*` |
| 테스트 | H2 메모리 DB | `mvnw.cmd test` 가 알아서 쓴다 (`.env` 와 무관) |

- 엔티티를 바꾸면 `db/migration` 에 새 `V번호__설명.sql` 을 추가한다. 이미 올린 SQL 파일은 고치지 않는다.
- H2 는 PostgreSQL 호환 모드로 돌고 행 잠금도 지켜서, 동시 신청(정원·재고) 테스트를 개발 PC 에서도 확인할 수 있다.

## 구조

패키지는 설계 산출물(2장 아키텍처도)의 서브시스템과 1:1 로 대응하고, 클래스·메소드 이름은 각 서브시스템의 클래스도를 따른다.

| 패키지 | 서브시스템 | 주요 클래스 |
|---|---|---|
| `ui` | User Interface | `MainView`, `MemberView`, `TravelHistoryView`, `TravelProductView`, `TravelApplicationView`, `TourProductAdminView`, `SouvenirStockView` (+ 추가 화면 `SouvenirAdminView`, `SMSLogView`) |
| `members` | Member Management | `MemberManagementAPI`, `MemberManager`, `Member`, `LoginSession` |
| `travel` | Travel Management | `TravelManagementAPI`, `TravelManager`, `TravelProduct`, `TravelConfiguration`, `TravelPriceCalculator`, `TravelApplication`, `TravelHistory`, `SouvenirStockAPI`(기념품 재고, souvenir 가 구현) |
| `souvenir` | Souvenir Management | `SouvenirManagementAPI`, `SouvenirManager`, `Souvenir`, `StockReceipt` (+ 출발 확정 차감 `StockIssue`) |
| `dataaccess` | Data Access | `DataAccessAPI`(업무별 저장 인터페이스를 묶음), `DataAccessManager`, `MemberRepository`, `TravelRepository`, `SouvenirRepository` (+ 문자 기록 `SMSMessageRepository`) |
| `speech` | Speech Recognition Integration | `SpeechRecognitionAPI`, `SpeechRecognitionManager`, `SpeechServiceClient`, `VoiceSelectionInterpreter` |
| `sms` | SMS Notification | `SMSNotificationAPI`, `SMSNotificationManager`, `SMSServiceClient`, `SMSMessage` |
| `common` | (설정) | 보안·로그인 설정, 웹 설정, 프로젝트 설정 값 |
| `demo` | (데모) | 데모 데이터 `--seed-demo` |

- 화면(View)은 업무 서브시스템의 `*API`/`*Manager` 로만 요청하고, 업무 클래스는 자기 패키지의 저장 인터페이스(`TravelDataAccessAPI` 등)로만 저장·조회한다. 이 인터페이스는 `DataAccessManager` 가 구현하므로 의존은 dataaccess → 업무 한 방향이다.
- 다른 서브시스템의 엔티티는 id 로 참조하고 직접 수정하지 않는다.
- 구현 서브시스템(계층)은 폴더, 그 안의 패키지마다 Maven 모듈(jar)이 하나다 (예: `mwt-business/mwt-travel`). 모듈 의존은 화면 → 업무 → 설정, 저장 → 업무 방향만 있고, 거꾸로 참조하면 컴파일되지 않는다.

```
pom.xml, mvnw, mvnw.cmd       부모 Maven 설정 (Spring Boot 4.1, Java 21, 모듈 목록)과 Maven Wrapper
계층 폴더(묶음 pom) / 패키지 모듈(jar 하나씩)
mwt-configuration/mwt-common/ Configuration: common (보안·웹 설정, 설정 값)
mwt-business/                 Business Layer
  mwt-members/ mwt-travel/ mwt-souvenir/ mwt-speech/ mwt-sms/
mwt-persistence/mwt-dataaccess/   Persistence Layer: dataaccess, resources/db/migration (Flyway 표 생성 SQL)
mwt-presentation/mwt-ui/      Presentation Layer: ui, resources/templates (Thymeleaf), resources/static (CSS·JS·기본 사진,
                              출처: static/img/photos/credits.json → 사이트 푸터 "사진 출처")
mwt-app/                      실행 모듈: MwtApplication, demo, application*.yml, 테스트(src/test, JUnit 5·MockMvc)
                              → 실행 jar mwt-app/target/mwt.jar (패키지 모듈 jar 8개를 묶음)
deploy/                       서버 설치·갱신 스크립트와 배포 안내
docs/                         계획서·요구분석·설계 산출물 (StarUML, Word, 한글)
```
