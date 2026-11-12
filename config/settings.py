"""
미스터 월드 투어 Django 설정.

환경별 값(비밀키, DB 접속 정보 등)은 프로젝트 루트의 .env 파일에서 읽는다.
.env.example 을 복사해 .env 를 만들어 사용한다. .env 는 git 에 올리지 않는다.
"""

import os
import sys
from pathlib import Path

from dotenv import load_dotenv

BASE_DIR = Path(__file__).resolve().parent.parent
load_dotenv(BASE_DIR / ".env")


def env_bool(name: str, default: bool = False) -> bool:
    return os.getenv(name, str(default)).lower() in ("1", "true", "yes", "on")


# 보안 ---------------------------------------------------------------
SECRET_KEY = os.getenv("DJANGO_SECRET_KEY", "django-insecure-dev-only-change-me")
DEBUG = env_bool("DJANGO_DEBUG", True)
ALLOWED_HOSTS = [h for h in os.getenv("DJANGO_ALLOWED_HOSTS", "localhost,127.0.0.1").split(",") if h]
CSRF_TRUSTED_ORIGINS = [o for o in os.getenv("DJANGO_CSRF_TRUSTED_ORIGINS", "").split(",") if o]
# 개발 중에만 같은 사이트 안의 iframe 표시 허용 (모바일 화면 미리보기용). 운영은 기본값 DENY
if DEBUG:
    X_FRAME_OPTIONS = "SAMEORIGIN"

if not DEBUG and SECRET_KEY.startswith("django-insecure"):
    raise RuntimeError("운영 모드(DJANGO_DEBUG=False)에서는 .env 의 DJANGO_SECRET_KEY 를 반드시 설정해야 합니다.")

# HTTPS 운영 (Nginx 가 HTTPS 를 처리하고 Gunicorn 으로 넘긴다)
if env_bool("DJANGO_HTTPS", False):
    SECURE_PROXY_SSL_HEADER = ("HTTP_X_FORWARDED_PROTO", "https")
    SESSION_COOKIE_SECURE = True
    CSRF_COOKIE_SECURE = True
    SECURE_SSL_REDIRECT = False  # 리다이렉트는 Nginx 가 담당
    SECURE_HSTS_SECONDS = 3600   # 과제 서버라 짧게


# 앱 -----------------------------------------------------------------
INSTALLED_APPS = [
    "django.contrib.admin",
    "django.contrib.auth",
    "django.contrib.contenttypes",
    "django.contrib.sessions",
    "django.contrib.messages",
    "django.contrib.staticfiles",
    "django.contrib.humanize",  # 금액 천 단위 쉼표(intcomma)
    # 설계 서브시스템별 앱 (Data Access 는 각 앱의 models.py / Django ORM 이 담당)
    "apps.members",   # Member Management
    "apps.travel",    # Travel Management
    "apps.souvenir",  # Souvenir Management
    "apps.speech",    # Speech Recognition Integration
    "apps.sms",       # SMS Notification
]

MIDDLEWARE = [
    "django.middleware.security.SecurityMiddleware",
    "django.contrib.sessions.middleware.SessionMiddleware",
    "django.middleware.common.CommonMiddleware",
    "django.middleware.csrf.CsrfViewMiddleware",
    "django.contrib.auth.middleware.AuthenticationMiddleware",
    "django.contrib.messages.middleware.MessageMiddleware",
    "django.middleware.clickjacking.XFrameOptionsMiddleware",
]

ROOT_URLCONF = "config.urls"

TEMPLATES = [
    {
        "BACKEND": "django.template.backends.django.DjangoTemplates",
        "DIRS": [BASE_DIR / "templates"],
        "APP_DIRS": True,
        "OPTIONS": {
            "context_processors": [
                "django.template.context_processors.request",
                "django.contrib.auth.context_processors.auth",
                "django.contrib.messages.context_processors.messages",
                "apps.travel.context_processors.site",
                "apps.members.context_processors.history_popup",
            ],
        },
    },
]

WSGI_APPLICATION = "config.wsgi.application"


# 데이터베이스 ---------------------------------------------------------
# POSTGRES_DB 가 설정되어 있으면 PostgreSQL, 없으면 로컬 개발용 SQLite 를 사용한다.
if os.getenv("POSTGRES_DB"):
    DATABASES = {
        "default": {
            "ENGINE": "django.db.backends.postgresql",
            "NAME": os.getenv("POSTGRES_DB"),
            "USER": os.getenv("POSTGRES_USER", "postgres"),
            "PASSWORD": os.getenv("POSTGRES_PASSWORD", ""),
            # localhost 는 Windows 에서 IPv6(::1)를 먼저 시도해 연결이 수 초씩 지연될 수 있어 IPv4 주소를 기본으로 쓴다.
            "HOST": os.getenv("POSTGRES_HOST", "127.0.0.1"),
            "PORT": os.getenv("POSTGRES_PORT", "5432"),
            "OPTIONS": {"connect_timeout": 5},  # DB 에 닿지 않으면 무한 대기하지 않고 5초 뒤 오류
        }
    }
else:
    DATABASES = {
        "default": {
            "ENGINE": "django.db.backends.sqlite3",
            "NAME": BASE_DIR / "db.sqlite3",
        }
    }


# 인증 ---------------------------------------------------------------
# 설계의 Member 클래스(고객·직원 공통 회원, role 로 구분)를 사용자 모델로 쓴다.
AUTH_USER_MODEL = "members.Member"
LOGIN_URL = "members:login"
LOGIN_REDIRECT_URL = "home"
LOGOUT_REDIRECT_URL = "home"

# 직원 가입 인증 코드 (임시 정책: 설계상 직원 권한 부여 방식은 후속 구체화 대상)
# 직원 유형으로 가입하려면 이 코드를 입력해야 한다.
STAFF_SIGNUP_CODE = os.getenv("STAFF_SIGNUP_CODE", "MWT-STAFF-2026")

AUTH_PASSWORD_VALIDATORS = [
    {"NAME": "django.contrib.auth.password_validation.UserAttributeSimilarityValidator"},
    {"NAME": "django.contrib.auth.password_validation.MinimumLengthValidator"},
    {"NAME": "django.contrib.auth.password_validation.CommonPasswordValidator"},
    {"NAME": "django.contrib.auth.password_validation.NumericPasswordValidator"},
]


# 국제화 -------------------------------------------------------------
LANGUAGE_CODE = "ko-kr"
TIME_ZONE = "Asia/Seoul"
USE_I18N = True
USE_TZ = True


# 정적 파일 ------------------------------------------------------------
STATIC_URL = "static/"
STATICFILES_DIRS = [BASE_DIR / "static"]
STATIC_ROOT = BASE_DIR / "staticfiles"  # 배포 시 collectstatic 대상 (Nginx 가 제공)

# 업로드 파일 (직원이 올린 상품 사진). 배포 시 Nginx 가 /media/ 로 제공
MEDIA_URL = "media/"
MEDIA_ROOT = BASE_DIR / "media"
FILE_UPLOAD_MAX_MEMORY_SIZE = 5 * 1024 * 1024
DATA_UPLOAD_MAX_MEMORY_SIZE = 6 * 1024 * 1024

DEFAULT_AUTO_FIELD = "django.db.models.BigAutoField"


# 외부 서비스 ---------------------------------------------------------
# 업체 미정: 클래스 경로로 교체한다. 기본값은 개발용 가짜 구현.
SMS_CLIENT = os.getenv("SMS_CLIENT", "apps.sms.services.ConsoleSMSClient")
# 솔라피 (SMS_CLIENT=apps.sms.solapi.SolapiSMSClient 일 때 사용)
SOLAPI_API_KEY = os.getenv("SOLAPI_API_KEY", "")
SOLAPI_API_SECRET = os.getenv("SOLAPI_API_SECRET", "")
SOLAPI_SENDER = os.getenv("SOLAPI_SENDER", "")  # 솔라피에 등록한 발신번호
SMS_TIMEOUT = int(os.getenv("SMS_TIMEOUT", "10"))  # 외부 호출 1회 대기 (초)
# 신청 1건이 보내는 문자 묶음 전체의 시간 한도 (초). gunicorn --timeout 60 보다 충분히 작게.
SMS_BATCH_TIME_LIMIT = int(os.getenv("SMS_BATCH_TIME_LIMIT", "40"))
# 비워 두면 모든 번호로 발송. 값이 있으면 이 번호들로만 실제 발송 (데모 계정의 가상 번호로 문자가 나가지 않게)
SMS_ALLOWED_NUMBERS = [n.strip() for n in os.getenv("SMS_ALLOWED_NUMBERS", "").split(",") if n.strip()]
SPEECH_CLIENT = os.getenv("SPEECH_CLIENT", "apps.speech.services.BrowserTranscriptClient")


# 로그 ---------------------------------------------------------------
# apps.* 의 INFO 이상을 콘솔(runserver 창)에 출력한다. 가짜 문자 발송 내용도 여기서 보인다.
LOGGING = {
    "version": 1,
    "disable_existing_loggers": False,
    "formatters": {"simple": {"format": "[{asctime}] {levelname} {name}: {message}", "style": "{",
                              "datefmt": "%H:%M:%S"}},
    "handlers": {"console": {"class": "logging.StreamHandler", "formatter": "simple"}},
    "loggers": {"apps": {"handlers": ["console"], "level": "INFO", "propagate": False}},
}
if "test" in sys.argv:
    LOGGING["loggers"]["apps"]["level"] = "WARNING"  # 테스트 출력은 경고 이상만
    PASSWORD_HASHERS = ["django.contrib.auth.hashers.MD5PasswordHasher"]  # 테스트 속도용 (운영에는 영향 없음)
    SMS_CLIENT = "apps.sms.services.ConsoleSMSClient"  # 테스트는 .env 와 상관없이 실제 문자를 보내지 않음
    import tempfile

    MEDIA_ROOT = Path(tempfile.gettempdir()) / "mwt-test-media"  # 테스트 업로드가 실제 media/ 에 섞이지 않게
