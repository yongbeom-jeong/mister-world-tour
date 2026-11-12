# 미스터 월드 투어 (Mister World Tour)

**2026년 2학기 소프트웨어공학 · 3인 팀 프로젝트 · 진행 중**

테마 여행 상품의 **조회·신청·출발 확정**과 기념품 재고 관리를 구현하는 Django 웹 애플리케이션입니다.

## 프로젝트 소개

- **목표:** 허니문·효도·골프·트레킹 상품별 신청 규칙과 재고 조건을 하나의 서비스에서 일관되게 처리
- **기술:** Python 3.13, Django 5.2, Django Template, Bootstrap 5, SQLite(로컬), PostgreSQL(서버 구성)
- **협업:** 요구사항 분석, 유스케이스·시퀀스 다이어그램, 브랜치 및 Pull Request 기반 변경 관리
- **배포 구성:** AWS EC2 / Ubuntu / Nginx / Gunicorn / PostgreSQL 설치·갱신 스크립트 (실제 배포 완료 여부와 별개)
- **상태:** 기능 개발과 검증을 진행 중이며 외부 SMS 연동은 환경 설정에 따라 모의 또는 실제 클라이언트로 전환 가능

## 핵심 설계 문제

| 상황 | 결정·구현 방향 |
| --- | --- |
| 여행 유형마다 출발 확정 조건이 다름 | 허니문은 2인 신청 단위, 그 외 테마는 3인 이상 모집 시 출발 확정 |
| 신청·정원·재고를 함께 처리해야 함 | 신청 흐름에서 조건 검사 및 트랜잭션 기반 처리 설계 |
| 같은 입고 요청이 재전송될 수 있음 | 요청 키로 중복 입고 반영을 막는 흐름 구성 |
| 문자 발송 결과가 불분명할 수 있음 | 명확한 실패와 결과 불명을 구분하고, 결과 불명 시 자동 재발송하지 않음 |
| 구성원마다 요구사항 해석이 다를 수 있음 | UML 설계 산출물과 변경 기록을 기준으로 의사결정 공유 |

## 구현 및 검증 참고

- [요구사항·설계 자료](docs/)
- [문자 발송 서비스](apps/sms/services.py) · [문자 서비스 테스트](apps/sms/tests.py)
- [배포 가이드](deploy/DEPLOY.md)
- [변경 이력 및 리뷰](../../pulls)

**팀 프로젝트 참고:** 실제 개인별 기여 내용은 PR, 커밋, 팀별 업무 분담 기록을 통해 확인해야 합니다. 저장소에 포함된 전체 기능을 특정 개인이 단독 개발했다고 주장하지 않습니다.

---

## 개발 환경과 실행 가이드

## 처음 설정 (Windows PowerShell)

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1          # 오류 시: Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
pip install -r requirements.txt
Copy-Item .env.example .env           # 필요하면 값 수정 (비워 두면 SQLite 사용)
python manage.py migrate
python manage.py seed_demo            # 데모 계정·상품·재고·이력 (비밀번호 demo1234!)
python manage.py createsuperuser      # /admin 관리자 계정 (선택)
python manage.py runserver            # http://127.0.0.1:8000
```

## 서버 배포

AWS EC2(Ubuntu 24.04)에 Nginx·Gunicorn·PostgreSQL 18 로 배포한다. 절차: [deploy/DEPLOY.md](deploy/DEPLOY.md)
(`deploy/setup.sh` 한 번 실행으로 설치·HTTPS 까지 완료, 갱신은 `deploy/update.sh`)

## 데이터베이스

| 환경 | DB | 설정 |
|---|---|---|
| 개발 PC | SQLite (`db.sqlite3`, 설치 불필요) | `.env`의 `POSTGRES_DB`를 비워 둠 |
| 배포 서버 (AWS) | PostgreSQL 18 | `.env`에 `POSTGRES_DB`·`POSTGRES_USER`·`POSTGRES_PASSWORD` 입력 |

- 코드 수정 없이 `.env`만으로 전환된다. PostgreSQL 드라이버(`psycopg`)는 `requirements.txt`에 포함되어 있다.
- DB 파일은 공유하지 않는다. 테이블 구조는 migration 파일, 데모 데이터는 `seed_demo`로 각자 만든다.
- `POSTGRES_HOST`는 `localhost` 대신 `127.0.0.1`을 쓴다 (Windows에서 IPv6 우선 시도로 연결이 수 초씩 지연됨).
- 배포 후 서버에서 `python manage.py test`를 한 번 실행해 PostgreSQL에서도 통과하는지 확인한다 (2026-11-02 PostgreSQL 18.6에서 22개 통과 확인).

## 자주 쓰는 명령

| 작업 | 명령 |
|---|---|
| 개발 서버 | `python manage.py runserver` |
| 테스트 | `python manage.py test --noinput` |
| 데모 데이터 | `python manage.py seed_demo` |
| 모델 변경 반영 | `python manage.py makemigrations` → `python manage.py migrate` |
| 패키지 추가 후 | `pip freeze > requirements.txt` |

**다른 사람이 올린 코드를 받은 뒤(git pull)** 에는 가상환경을 켠 상태에서 아래를 실행한다.
패키지·DB 구조·데모 사진이 바뀌었을 수 있다. 여러 번 실행해도 안전하다.

```powershell
pip install -r requirements.txt
python manage.py migrate
python manage.py seed_demo
```

## 구조

```
config/          Django 설정·URL
apps/
  members/       회원 관리 (Member: 고객·직원, 로그인)
  travel/        여행 관리 (상품·구성·신청·할인·출발 확정)
  souvenir/      기념품 관리 (품목 등록·입고·출발 확정 시 차감)
  speech/        음성인식 연동
  sms/           문자 발송 연동
templates/       화면 템플릿 (base.html 공통 틀, 앱별 폴더)
static/
  css/mwt.css    공통 디자인 (색·글꼴·카드 등, 규칙은 AGENTS.md "화면 디자인")
  img/photos/    기본 사진 (위키미디어 공용, 출처: credits.json → 사이트 푸터 "사진 출처")
media/           직원이 올린 상품 사진 (git 제외, seed_demo 가 데모 사진을 넣음)
deploy/          서버 설치·갱신 스크립트와 배포 안내
docs/            요구분석·설계 산출물 (StarUML, Word)
```

## 데모 계정 (`seed_demo`, 비밀번호 모두 `demo1234!`)

| 아이디 | 유형 | 용도 |
|---|---|---|
| `staff1` | 직원 | 상품 등록·관리, 기념품 등록·입고 |
| `regular1` | 고객 | 단골 (최근 3년 완료 여행 3회 → 10% 할인) |
| `customer2` | 고객 | 비단골 (3년 내 2회) |
| `newbie` | 고객 | 이력 없음 |

직원 유형으로 새로 가입할 때는 직원 인증 코드(`.env`의 `STAFF_SIGNUP_CODE`, 기본 `MWT-STAFF-2026`)가 필요하다.

## 외부 서비스 (음성인식·문자)

기본은 가짜 구현으로 동작하고, `.env`에서 클래스 경로로 교체한다. 문자는 솔라피로 실제 발송할 수 있다(아래).

| 기능 | 현재 동작 | 확인 방법 |
|---|---|---|
| 음성 선택 | 브라우저 내장 음성인식(Chrome·Edge, 한국어) | 고객 → 여행 상품 → 🎤 버튼. 마이크가 없으면 "문장으로 입력" 칸 사용 |
| 출발 확정 문자 | 실제로 보내지 않고 `runserver` 창에 `[SMS 가짜 발송]` 로그 | 신청 결과 화면의 "출발 확정 문자: 성공 N건" |
| 문자 재시도 시연 | `.env`에 `SMS_CLIENT=apps.sms.services.FlakyDemoSMSClient` | 번호마다 2번 실패 후 3번째 성공이 로그에 보임 |
| 실제 문자 (솔라피) | `.env`에 `SMS_CLIENT=apps.sms.solapi.SolapiSMSClient` + 솔라피 키·발신번호 | `manage.py send_test_sms 010-xxxx-xxxx` 로 시험 발송 |

### 솔라피로 실제 문자 보내기

1. [솔라피](https://solapi.com) 가입 → 잔액 충전 (단문 18원. 출발 확정 문자는 단문 90byte 안에 맞춰 보낸다. 첫 실명인증 시 300포인트)
2. 콘솔에서 **발신번호 등록** (본인 휴대폰 인증) → **API Key 발급** (Key·Secret)
3. `.env` 설정 후 서버 재시작

   ```
   SMS_CLIENT=apps.sms.solapi.SolapiSMSClient
   SOLAPI_API_KEY=발급받은 키
   SOLAPI_API_SECRET=발급받은 시크릿
   SOLAPI_SENDER=01012345678
   SMS_ALLOWED_NUMBERS=010-1111-2222,010-3333-4444
   ```

4. `.venv/Scripts/python manage.py send_test_sms 010-1111-2222` 로 휴대폰 수신 확인

`SMS_ALLOWED_NUMBERS`에 넣은 번호로만 실제 발송된다. 데모 계정(`seed_demo`)의 연락처는 임의 번호이므로,
시연 중에는 팀원 번호만 넣어 두고 팀원 번호로 가입한 고객 계정으로 신청한다. 목록 밖 번호는 "실패"로 기록되고 재시도하지 않는다.
