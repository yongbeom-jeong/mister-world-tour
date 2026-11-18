# 미스터 월드 투어 (Mister World Tour)

**2026년 2학기 소프트웨어공학 · 3인 팀 프로젝트 · 진행 중**

테마 여행 상품의 **조회·신청·출발 확정**과 기념품 재고 관리를 구현하는 Java Spring Boot 웹 애플리케이션입니다.

> 이 브랜치(`spring`)는 Spring Boot 판입니다. 처음 만든 Django 판은 `main` 브랜치에 따로 있으며 두 브랜치는 합치지 않습니다.

## 프로젝트 소개

- **목표:** 허니문·효도·골프·트레킹 상품별 신청 규칙과 재고 조건을 하나의 서비스에서 일관되게 처리
- **기술:** Java 21, Spring Boot 4.1, Spring MVC + Thymeleaf, Spring Security, Spring Data JPA, Flyway, Bootstrap 5, H2(로컬), PostgreSQL 18(서버)
- **협업:** 요구사항 분석, 유스케이스·시퀀스·클래스 다이어그램, 브랜치 및 Pull Request 기반 변경 관리
- **설계와 구현:** 패키지·클래스·메소드 이름을 설계 산출물과 맞추고, 구현 서브시스템 3개·컴포넌트 7개를 Maven 모듈(jar)로 나눔
- **배포 구성:** AWS EC2 / Ubuntu / Nginx / systemd(jar) / PostgreSQL 설치·갱신 스크립트
- **상태:** 기능 개발과 검증을 진행 중이며 외부 SMS 연동은 환경 설정에 따라 모의 또는 실제 클라이언트로 전환 가능

## 핵심 설계 문제

| 상황 | 결정·구현 방향 |
| --- | --- |
| 여행 유형마다 출발 확정 조건이 다름 | 허니문은 2인 신청 단위, 그 외 테마는 3인 이상 모집 시 출발 확정 |
| 신청·정원·재고를 함께 처리해야 함 | 상품 행을 잠근 한 트랜잭션(`runTravelTransaction`)에서 정원·재고·금액을 다시 검사하고 저장 |
| 같은 입고 요청이 재전송될 수 있음 | 요청 키로 중복 입고 반영을 막는 흐름 구성 |
| 문자 발송 결과가 불분명할 수 있음 | 명확한 실패와 결과 불명을 구분하고, 결과 불명 시 자동 재발송하지 않음 |
| 서브시스템 사이 의존이 얽힐 수 있음 | 다른 서브시스템은 `*API` 로만 호출하고, 저장 인터페이스는 업무 쪽이 갖도록 의존성 역전 (`PackageDependencyTest` 로 검사) |
| 구성원마다 요구사항 해석이 다를 수 있음 | UML 설계 산출물과 변경 기록을 기준으로 의사결정 공유 |

## 구현 및 검증 참고

- [요구사항·설계·구현 산출물](docs/)
- [신청·출발 확정](mwt-business/mwt-travel/src/main/java/com/misterworldtour/travel/TravelManager.java) · [신청 테스트](mwt-app/src/test/java/com/misterworldtour/travel/BookingServiceTest.java)
- [문자 발송 서비스](mwt-business/mwt-sms/src/main/java/com/misterworldtour/sms/SMSNotificationManager.java) · [문자 서비스 테스트](mwt-app/src/test/java/com/misterworldtour/sms/SMSDeliveryTest.java)
- [배포 가이드](deploy/DEPLOY.md)
- [변경 이력 및 리뷰](../../pulls)

**팀 프로젝트 참고:** 실제 개인별 기여 내용은 PR, 커밋, 팀별 업무 분담 기록을 통해 확인해야 합니다. 저장소에 포함된 전체 기능을 특정 개인이 단독 개발했다고 주장하지 않습니다.

---

## 개발 환경과 실행 가이드

## 처음 설정 (Windows PowerShell)

Java 21 이상(JDK)만 있으면 된다. Maven 은 저장소의 Maven Wrapper(`mvnw.cmd`)가 받아 쓴다.

```powershell
java -version                          # 21 이상인지 확인
Copy-Item .env.example .env            # 필요하면 값 수정 (직원 가입 코드, 문자 설정 등)
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--seed-demo"   # 데모 데이터와 함께 실행, http://localhost:8080
```

- 표(테이블)는 시작할 때 Flyway 가 만든다. 따로 migrate 할 필요가 없다.
- 포트를 바꾸려면 `PORT` 환경변수를 쓴다 (예: `$env:PORT=8081`).

## 서버 배포

AWS EC2(Ubuntu 24.04)에 Nginx·Java 21·PostgreSQL 18 로 배포한다. 절차: [deploy/DEPLOY.md](deploy/DEPLOY.md)
(`deploy/setup.sh` 한 번 실행으로 설치·HTTPS 까지 완료, 갱신은 `deploy/update.sh`. 서버에서는 systemd 서비스 `mwt-spring` 이 `mwt.jar` 를 실행)

## 데이터베이스

| 환경 | DB | 설정 |
|---|---|---|
| 개발 PC | H2 파일 DB (`data/`, 설치 불필요) | 기본값. 처음 상태로 돌리려면 서버를 끄고 `data/` 를 지운다 |
| 배포 서버 (AWS) | PostgreSQL 18 | `--spring.profiles.active=prod` + `.env`에 `POSTGRES_DB`·`POSTGRES_USER`·`POSTGRES_PASSWORD` 입력 |
| 테스트 | H2 메모리 DB | `mvnw.cmd test` 가 알아서 쓴다 (`.env` 와 무관) |

- 코드 수정 없이 실행 설정(프로필)과 `.env`만으로 전환된다. PostgreSQL 드라이버는 Maven 의존성에 포함되어 있다.
- DB 파일은 공유하지 않는다. 테이블 구조는 Flyway SQL, 데모 데이터는 `--seed-demo`로 각자 만든다.
- 엔티티를 바꾸면 `mwt-persistence/mwt-dataaccess/src/main/resources/db/migration` 에 새 `V번호__설명.sql` 을 추가한다. 이미 올린 SQL 은 고치지 않는다.
- H2 는 PostgreSQL 호환 모드로 돌고 행 잠금도 지켜서, 동시 신청(정원·재고) 테스트 `BookingConcurrencyTest` 를 개발 PC 에서도 돌릴 수 있다.

## 자주 쓰는 명령

| 작업 | 명령 |
|---|---|
| 개발 서버 | `.\mvnw.cmd spring-boot:run` |
| 테스트 | `.\mvnw.cmd test` (1분 내외) |
| 데모 데이터 | `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--seed-demo"` |
| jar 만들기 | `.\mvnw.cmd package` → `mwt-app/target/mwt.jar` |
| 완료 여행 이력 일괄 정리 | `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--complete-trips"` |
| 시험 문자 1통 | `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--send-test-sms=010-1234-5678"` |

**다른 사람이 올린 코드를 받은 뒤(git pull)** 에는 아래를 실행한다.
의존 라이브러리·DB 구조·데모 데이터가 바뀌었을 수 있다 (Maven 이 라이브러리를 받고, Flyway 가 표를 갱신한다). 여러 번 실행해도 안전하다.

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--seed-demo"
```

## 구조

구현 서브시스템(계층)은 폴더이고, 그 안의 패키지(컴포넌트)마다 Maven 모듈(jar)이 하나다.
의존은 화면 → 업무, 저장 → 업무 방향만 있어서 거꾸로 참조하면 컴파일되지 않는다.

```
pom.xml, mvnw     부모 Maven 설정(모듈 목록)과 Maven Wrapper
mwt-presentation/
  mwt-ui/         화면 (View·입력 양식, 보안·웹 설정)
                  resources/templates  화면 템플릿 (layout.html 공통 틀, 기능별 폴더)
                  resources/static     css/mwt.css 공통 디자인 (규칙은 AGENTS.md "화면 디자인"),
                                       img/photos/ 기본 사진 (위키미디어 공용, 출처: credits.json → 사이트 푸터 "사진 출처")
mwt-business/
  mwt-members/    회원 관리 (Member: 고객·직원, 로그인)
  mwt-travel/     여행 관리 (상품·구성·신청·할인·출발 확정·완료 이력)
  mwt-souvenir/   기념품 관리 (품목 등록·입고·출발 확정 시 차감)
  mwt-speech/     음성인식 연동
  mwt-sms/        문자 발송 연동
mwt-persistence/
  mwt-dataaccess/ 데이터 저장 (업무 패키지의 저장 인터페이스 구현), resources/db/migration (Flyway SQL)
mwt-app/          실행 모듈 (MwtApplication, 데모 데이터, application*.yml, 테스트) → mwt-app/target/mwt.jar
media/            직원이 올린 상품 사진 (git 제외)
deploy/           서버 설치·갱신 스크립트와 배포 안내
docs/             요구분석·설계·구현운영 산출물 (StarUML, Word)
```

## 데모 계정 (`--seed-demo`, 비밀번호 모두 `demo1234!`)

| 아이디 | 유형 | 용도 |
|---|---|---|
| `staff1` | 직원 | 상품 등록·관리, 기념품 등록·입고, 문자 발송 기록 |
| `regular1` | 고객 | 단골 (최근 3년 완료 여행 3회 → 10% 할인) |
| `customer2` | 고객 | 비단골 (3년 내 2회) |
| `newbie` | 고객 | 이력 없음 |

직원 유형으로 새로 가입할 때는 직원 인증 코드(`.env`의 `STAFF_SIGNUP_CODE`, 기본 `MWT-STAFF-2026`)가 필요하다.

## 외부 서비스 (음성인식·문자)

기본은 가짜 구현으로 동작하고, `.env`의 `SMS_CLIENT` 값으로 교체한다. 문자는 솔라피로 실제 발송할 수 있다(아래).

| 기능 | 현재 동작 | 확인 방법 |
|---|---|---|
| 음성 선택 | 브라우저 내장 음성인식(Chrome·Edge, 한국어) | 고객 → 여행 상품 → 마이크 버튼. 마이크가 없으면 같은 칸에 문장을 입력 |
| 출발 확정 문자 | 실제로 보내지 않고 실행 창에 `[SMS 가짜 발송]` 로그 (`SMS_CLIENT=Console`, 기본) | 직원 → 문자 기록 화면의 발송 결과 |
| 문자 재시도 시연 | `.env`에 `SMS_CLIENT=FlakyDemo` | 번호마다 2번 실패 후 3번째 성공이 로그에 보임 |
| 실제 문자 (솔라피) | `.env`에 `SMS_CLIENT=Solapi` + 솔라피 키·발신번호 | `--send-test-sms=010-xxxx-xxxx` 로 시험 발송 |

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

4. `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--send-test-sms=010-1111-2222"` 로 휴대폰 수신 확인

`SMS_ALLOWED_NUMBERS`에 넣은 번호로만 실제 발송된다. 데모 계정(`--seed-demo`)의 연락처는 임의 번호이므로,
시연 중에는 팀원 번호만 넣어 두고 팀원 번호로 가입한 고객 계정으로 신청한다. 목록 밖 번호는 "실패"로 기록되고 재시도하지 않는다.
