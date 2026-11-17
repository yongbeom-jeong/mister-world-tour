# 미스터 월드 투어 (Mister World Tour)

**2026년 2학기 소프트웨어공학 · 3인 팀 프로젝트**

테마 여행 상품의 **조회·신청·출발 확정**과 기념품 재고 관리를 구현하는 Java Spring Boot 웹 애플리케이션입니다.

> 이 브랜치(`spring`)는 Spring Boot 판입니다. 처음 만든 Django 판은 `main` 브랜치에 그대로 있습니다.
> 화면·URL·업무 규칙은 두 판이 같습니다.

## 프로젝트 소개

- **목표:** 허니문·효도·골프·트레킹 상품별 신청 규칙과 재고 조건을 하나의 서비스에서 일관되게 처리
- **기술:** Java 21, Spring Boot 4.1, Spring MVC + Thymeleaf, Spring Security, Spring Data JPA, Flyway, Bootstrap 5,
  H2(개발 PC), PostgreSQL 18(서버)
- **협업:** 요구사항 분석, 유스케이스·시퀀스 다이어그램, 브랜치 및 Pull Request 기반 변경 관리
- **배포 구성:** AWS EC2 / Ubuntu / Nginx / systemd(jar) / PostgreSQL 설치·갱신 스크립트 (실제 배포 완료 여부와 별개)
- **외부 연동:** 문자는 솔라피(설정에 따라 가짜 클라이언트로 전환), 음성은 브라우저 내장 음성인식

## 핵심 설계 문제

| 상황 | 결정·구현 방향 |
| --- | --- |
| 여행 유형마다 출발 확정 조건이 다름 | 허니문은 2인 신청 단위, 그 외 테마는 3인 이상 모집 시 출발 확정 |
| 신청·정원·재고를 함께 처리해야 함 | 상품 행을 잠근 한 트랜잭션에서 정원·재고·금액을 다시 검사하고 저장 |
| 같은 입고 요청이 재전송될 수 있음 | 요청 키로 중복 입고 반영을 막는 흐름 구성 |
| 문자 발송 결과가 불분명할 수 있음 | 명확한 실패와 결과 불명을 구분하고, 결과 불명 시 자동 재발송하지 않음 |
| 구성원마다 요구사항 해석이 다를 수 있음 | UML 설계 산출물과 변경 기록을 기준으로 의사결정 공유 |

## 구현 및 검증 참고

- [요구사항·설계 자료](docs/)
- [신청·출발 확정](spring/src/main/java/com/misterworldtour/travel/BookingService.java) · [신청 테스트](spring/src/test/java/com/misterworldtour/travel/BookingServiceTest.java)
- [문자 발송 서비스](spring/src/main/java/com/misterworldtour/sms/SmsService.java) · [문자 테스트](spring/src/test/java/com/misterworldtour/sms/SmsDeliveryTest.java)
- [배포 가이드](spring/deploy/DEPLOY.md)
- [Django → Spring 대응표](spring/README.md)
- [변경 이력 및 리뷰](../../pulls)

**팀 프로젝트 참고:** 실제 개인별 기여 내용은 PR, 커밋, 팀별 업무 분담 기록을 통해 확인해야 합니다. 저장소에 포함된 전체 기능을 특정 개인이 단독 개발했다고 주장하지 않습니다.

---

## 개발 환경과 실행 가이드

## 처음 설정 (Windows PowerShell)

Java 21(JDK)만 있으면 된다. Maven 은 저장소에 들어 있는 Maven Wrapper(`mvnw.cmd`)가 받아 쓴다.

```powershell
java -version                          # 21 이상인지 확인
Copy-Item .env.example .env            # 필요하면 값 수정 (STAFF_SIGNUP_CODE, 문자 설정 등)
cd spring
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--seed-demo"   # http://localhost:8080
```

- `--seed-demo` 는 데모 계정·상품·재고·이력·신청을 넣는다. 여러 번 실행해도 중복되지 않는다 (비밀번호 `demo1234!`).
- 표(테이블)는 시작할 때 Flyway 가 `spring/src/main/resources/db/migration` 의 SQL 로 만든다. `migrate` 같은 별도 명령이 없다.
- 포트를 바꾸려면 `PORT` 환경변수를 쓴다 (예: `$env:PORT=8081`).

## 서버 배포

AWS EC2(Ubuntu 24.04)에 Nginx·Java 21·PostgreSQL 18 로 배포한다. 서버는 이 `spring` 브랜치를 받아 jar 로 빌드한다.
절차: [spring/deploy/DEPLOY.md](spring/deploy/DEPLOY.md)
(`spring/deploy/setup.sh` 한 번 실행으로 설치·HTTPS 까지 완료, 갱신은 `spring/deploy/update.sh`,
systemd 서비스 `mwt-spring`). 같은 서버에서 Django 판과 나란히 띄울 수 있다.

## 데이터베이스

| 환경 | DB | 설정 |
|---|---|---|
| 개발 PC | H2 파일 DB (`spring/data/`, 설치 불필요) | 기본값. 처음 상태로 돌리려면 서버를 끄고 `spring/data/` 를 지운다 |
| 배포 서버 (AWS) | PostgreSQL 18, DB `mwt_spring` | `--spring.profiles.active=prod` + `.env`의 `POSTGRES_*` |
| 테스트 | H2 메모리 DB | `mvnw.cmd test` 가 알아서 쓴다 (`.env` 와 무관) |

- DB 파일은 공유하지 않는다. 표 구조는 Flyway SQL, 데모 데이터는 `--seed-demo`로 각자 만든다.
- 모델(엔티티)을 바꾸면 `db/migration` 에 새 `V번호__설명.sql` 을 추가한다. 이미 올린 SQL 파일은 고치지 않는다.
- H2 는 PostgreSQL 호환 모드로 돌고 행 잠금도 지켜서, 동시 신청(정원·재고) 테스트를 개발 PC 에서도 확인할 수 있다.

## 자주 쓰는 명령 (`spring/` 폴더에서)

| 작업 | 명령 |
|---|---|
| 개발 서버 | `.\mvnw.cmd spring-boot:run` |
| 데모 데이터와 함께 실행 | `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--seed-demo"` |
| 테스트 | `.\mvnw.cmd test` |
| jar 만들기 | `.\mvnw.cmd package` (`target/mwt-0.1.0.jar`) |
| 완료 여행 이력 일괄 정리 | `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--complete-trips"` |
| 시험 문자 1통 | `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--send-test-sms=010-1234-5678"` |

**다른 사람이 올린 코드를 받은 뒤(git pull)** 에는 그냥 다시 실행하면 된다. 의존성은 Maven 이, 표 변경은 Flyway 가
시작할 때 반영한다. 데모 데이터가 바뀌었으면 `--seed-demo` 를 붙여 실행한다.

## 구조

```
spring/
  pom.xml                     Maven 설정 (Spring Boot 4.1, Java 21)
  mvnw, mvnw.cmd              Maven Wrapper
  src/main/java/com/misterworldtour/
    common/                   보안·로그인 설정, 공통 화면 값, 홈·사진 출처, 안내 문구
    members/                  회원 관리 (Member: 고객·직원, 가입·로그인, 역할 검사)
    travel/                   여행 관리 (상품·구성·가격·신청·출발 확정·취소·완료 이력·직원 상품 관리)
    souvenir/                 기념품 관리 (품목 등록·입고·출발 확정 시 차감)
    speech/                   음성인식 연동 (음성·문장으로 상품 찾기)
    sms/                      문자 발송 연동 (출발 확정 문자, 발송 기록·재발송, 솔라피)
    demo/                     데모 데이터 (--seed-demo)
  src/main/resources/
    application.yml           설정 (prod 는 application-prod.yml)
    db/migration/             Flyway 표 생성 SQL
    templates/                Thymeleaf 화면 (layout.html 공통 틀, 패키지별 폴더)
  src/test/                   테스트 (JUnit 5, MockMvc)
  deploy/                     서버 설치·갱신 스크립트와 배포 안내
static/
  css/mwt.css                 공통 디자인 (빌드 때 jar 에 함께 묶음)
  img/photos/                 기본 사진 (위키미디어 공용, 출처: credits.json → 사이트 푸터 "사진 출처")
docs/                         요구분석·설계 산출물 (StarUML, Word)
```

패키지는 설계 산출물의 서브시스템과 1:1 로 대응한다. 컨트롤러는 Repository 를 직접 쓰지 않고 각 패키지의
`*Service` 로만 조회·변경하며, 다른 패키지의 엔티티는 id 로만 참조한다.
저장소 루트의 `apps/`·`config/`·`templates/`·`deploy/` 는 `main` 에서 이어진 Django 판 코드다 (Spring 판은 쓰지 않음).

## 데모 계정 (`--seed-demo`, 비밀번호 모두 `demo1234!`)

| 아이디 | 유형 | 용도 |
|---|---|---|
| `staff1` | 직원 | 상품 등록·관리, 기념품 등록·입고, 문자 기록 |
| `regular1` | 고객 | 단골 (최근 3년 완료 여행 3회 → 10% 할인) |
| `customer2` | 고객 | 비단골 (3년 내 2회). 다낭 골프 2명·제주 효도 8명 신청이 들어 있음 |
| `newbie` | 고객 | 이력 없음 |

직원 유형으로 새로 가입할 때는 직원 인증 코드(`.env`의 `STAFF_SIGNUP_CODE`, 기본 `MWT-STAFF-2026`)가 필요하다.
Django 판과 DB 가 달라 Django 판 계정으로는 로그인할 수 없다.

## 외부 서비스 (음성인식·문자)

기본은 가짜 구현으로 동작하고, `.env`의 `SMS_CLIENT` 로 교체한다. 문자는 솔라피로 실제 발송할 수 있다(아래).
값은 `Console`·`FlakyDemo`·`Solapi` 이름으로 쓰고, Django 판 값(`apps.sms.solapi.SolapiSMSClient` 등)도 그대로 알아듣는다.

| 기능 | 현재 동작 | 확인 방법 |
|---|---|---|
| 음성 선택 | 브라우저 내장 음성인식(Chrome·Edge, 한국어) | 고객 → 여행 상품 → 🎤 버튼. 마이크가 없으면 같은 칸에 문장을 입력 |
| 출발 확정 문자 | 실제로 보내지 않고 실행 창에 `[SMS 가짜 발송]` 로그 | 신청 결과 화면의 "출발 확정 문자: 성공 N건" |
| 문자 재시도 시연 | `.env`에 `SMS_CLIENT=FlakyDemo` | 번호마다 2번 실패 후 3번째 성공이 로그에 보임 |
| 실제 문자 (솔라피) | `.env`에 `SMS_CLIENT=Solapi` + 솔라피 키·발신번호 | `--send-test-sms=010-xxxx-xxxx` 로 시험 발송 |

테스트(`mvnw.cmd test`)는 `.env` 와 무관하게 항상 가짜 문자 클라이언트를 쓴다.

### 솔라피로 실제 문자 보내기

1. [솔라피](https://solapi.com) 가입 → 잔액 충전 (단문 18원. 출발 확정 문자는 단문 90byte 안에 맞춰 보낸다. 첫 실명인증 시 300포인트)
2. 콘솔에서 **발신번호 등록** (본인 휴대폰 인증) → **API Key 발급** (Key·Secret)
3. `.env` 설정 후 서버 재시작

   ```
   SMS_CLIENT=Solapi
   SOLAPI_API_KEY=발급받은 키
   SOLAPI_API_SECRET=발급받은 시크릿
   SOLAPI_SENDER=01012345678
   SMS_ALLOWED_NUMBERS=010-1111-2222,010-3333-4444
   ```

4. `spring/` 에서 `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--send-test-sms=010-1111-2222"` 로 휴대폰 수신 확인
   (보낸 뒤 프로그램이 끝나며, 종료 코드 0 성공 · 1 실패 · 2 결과 불명)

`SMS_ALLOWED_NUMBERS`에 넣은 번호로만 실제 발송된다. 데모 계정(`--seed-demo`)의 연락처는 임의 번호이므로,
시연 중에는 팀원 번호만 넣어 두고 팀원 번호로 가입한 고객 계정으로 신청한다. 목록 밖 번호는 "실패"로 기록되고 재시도하지 않는다.
