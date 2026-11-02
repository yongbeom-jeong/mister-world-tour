# 미스터 월드 투어 (Mister World Tour)

테마 여행(허니문·효도·골프·트레킹) 상품 판매와 기념품 재고를 관리하는 웹 시스템.
2026-2 소프트웨어공학 팀 프로젝트.

- 기술: Python 3.13 · Django 5.2 · Django Template · Bootstrap 5 · PostgreSQL
- 배포 예정: AWS EC2(Ubuntu) · Nginx · Gunicorn
- 요구분석·설계 산출물: [`docs/`](docs/)

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
| 테스트 | `python manage.py test` |
| 데모 데이터 | `python manage.py seed_demo` |
| 모델 변경 반영 | `python manage.py makemigrations` → `python manage.py migrate` |
| 패키지 추가 후 | `pip freeze > requirements.txt` |

## 구조

```
config/          Django 설정·URL
apps/
  members/       회원 관리 (Member: 고객·직원, 로그인)
  travel/        여행 관리 (상품·구성·신청·할인·출발 확정)
  souvenir/      기념품 관리 (품목·재고·입고)
  speech/        음성인식 연동
  sms/           문자 발송 연동
templates/       공통 템플릿 (base.html)
static/          CSS·JS
docs/            요구분석·설계 산출물 (StarUML, Word)
```

## 데모 계정 (`seed_demo`, 비밀번호 모두 `demo1234!`)

| 아이디 | 유형 | 용도 |
|---|---|---|
| `staff1` | 직원 | 상품 등록·관리, 기념품 입고 |
| `regular1` | 고객 | 단골 (최근 3년 완료 여행 3회 → 10% 할인) |
| `customer2` | 고객 | 비단골 (3년 내 2회) |
| `newbie` | 고객 | 이력 없음 |

직원 유형으로 새로 가입할 때는 직원 인증 코드(`.env`의 `STAFF_SIGNUP_CODE`, 기본 `MWT-STAFF-2026`)가 필요하다.

## 외부 서비스 (음성인식·문자)

업체가 정해지지 않아 가짜 구현으로 동작한다. `.env`에서 클래스 경로로 교체한다.

| 기능 | 현재 동작 | 확인 방법 |
|---|---|---|
| 음성 선택 | 브라우저 내장 음성인식(Chrome·Edge, 한국어) | 고객 → 여행 상품 → 🎤 버튼. 마이크가 없으면 "문장으로 입력" 칸 사용 |
| 출발 확정 문자 | 실제로 보내지 않고 `runserver` 창에 `[SMS 가짜 발송]` 로그 | 신청 결과 화면의 "출발 확정 문자: 성공 N건" |
| 문자 재시도 시연 | `.env`에 `SMS_CLIENT=apps.sms.services.FlakyDemoSMSClient` | 번호마다 2번 실패 후 3번째 성공이 로그에 보임 |
