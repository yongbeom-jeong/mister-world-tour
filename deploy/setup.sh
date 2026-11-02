#!/usr/bin/env bash
# 미스터 월드 투어 서버 설치 (Ubuntu 24.04, AWS EC2)
#
#   bash /srv/mwt/deploy/setup.sh
#
# 하는 일: 패키지 설치 → PostgreSQL 18 · DB 계정 → .env 생성 → 가상환경·migrate·데모 데이터·테스트
#          → Gunicorn(systemd) → Nginx → HTTPS 인증서(Let's Encrypt, sslip.io 무료 주소)
# 여러 번 실행해도 안전하다 (이미 있는 .env · DB · 인증서는 그대로 사용).
#
# 환경변수로 바꿀 수 있는 값
#   HOST=example.com      사용할 도메인 (기본: <공인IP>.sslip.io)
#   SKIP_HTTPS=1          인증서 발급 생략 (HTTP 만 사용)
#   SKIP_SEED=1           데모 데이터 생략

set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_USER="$(id -un)"
SERVICE=mwt
DB_NAME=mwt
DB_USER=mwt
PG_VERSION=18

step() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m    %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m    %s\033[0m\n' "$*"; }

cd "$APP_DIR"
[ -f manage.py ] || { echo "manage.py 가 없습니다. 저장소 폴더 안의 deploy/setup.sh 를 실행하세요."; exit 1; }

PUBLIC_IP="$(curl -fsS https://checkip.amazonaws.com | tr -d '[:space:]')"
HOST="${HOST:-${PUBLIC_IP//./-}.sslip.io}"
step "설치 위치 $APP_DIR · 사용자 $APP_USER · 공인 IP $PUBLIC_IP · 주소 $HOST"

# ---------------------------------------------------------------- 1. 패키지
step "1/7 패키지 설치"
export DEBIAN_FRONTEND=noninteractive
sudo apt-get update -y -q
sudo apt-get install -y -q python3-venv python3-dev git curl openssl nginx postgresql-common \
  certbot python3-certbot-nginx
if ! dpkg -s "postgresql-$PG_VERSION" >/dev/null 2>&1; then
  sudo /usr/share/postgresql-common/pgdg/apt.postgresql.org.sh -y   # PostgreSQL 공식 저장소
  sudo apt-get install -y -q "postgresql-$PG_VERSION"
fi
ok "PostgreSQL $(sudo -u postgres psql -tAc 'SHOW server_version')"

# 메모리가 작은 인스턴스(1GB)에서 pip 설치가 멈추지 않도록 스왑 1GB
if [ "$(swapon --show | wc -l)" -eq 0 ]; then
  sudo fallocate -l 1G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile >/dev/null && sudo swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
  ok "스왑 1GB 추가"
fi

# ---------------------------------------------------------------- 2. .env
step "2/7 .env (비밀값은 서버에서 무작위 생성, git 에 올리지 않음)"
set_env() {  # set_env KEY VALUE : .env 의 값을 바꾸거나 추가
  if grep -q "^$1=" .env; then sed -i "s|^$1=.*|$1=$2|" .env; else echo "$1=$2" >> .env; fi
}
if [ ! -f .env ]; then
  cp .env.example .env
  set_env DJANGO_SECRET_KEY "$(openssl rand -base64 48 | tr -d '\n/+=')"
  set_env POSTGRES_PASSWORD "$(openssl rand -hex 16)"
  set_env STAFF_SIGNUP_CODE "MWT-STAFF-$(openssl rand -hex 3 | tr a-f A-F)"
  ok ".env 새로 만듦"
else
  ok "기존 .env 사용"
fi
set_env DJANGO_DEBUG False
set_env DJANGO_ALLOWED_HOSTS "$HOST,$PUBLIC_IP"
set_env POSTGRES_DB "$DB_NAME"
set_env POSTGRES_USER "$DB_USER"
set_env POSTGRES_HOST 127.0.0.1
set_env POSTGRES_PORT 5432
DB_PASS="$(grep '^POSTGRES_PASSWORD=' .env | cut -d= -f2-)"
chmod 600 .env

# ---------------------------------------------------------------- 3. DB
step "3/7 데이터베이스"
if sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='$DB_USER'" | grep -q 1; then
  sudo -u postgres psql -q -c "ALTER ROLE $DB_USER WITH LOGIN CREATEDB PASSWORD '$DB_PASS'"
else
  sudo -u postgres psql -q -c "CREATE ROLE $DB_USER WITH LOGIN CREATEDB PASSWORD '$DB_PASS'"  # CREATEDB: 테스트용
fi
if ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='$DB_NAME'" | grep -q 1; then
  sudo -u postgres createdb -O "$DB_USER" -E UTF8 -T template0 "$DB_NAME"
fi
ok "DB '$DB_NAME' / 사용자 '$DB_USER'"

# ---------------------------------------------------------------- 4. 앱
step "4/7 가상환경 · migrate · 데모 데이터 · 테스트"
[ -d .venv ] || python3 -m venv .venv
.venv/bin/pip install -q --upgrade pip
.venv/bin/pip install -q -r requirements.txt
.venv/bin/python manage.py migrate --noinput
.venv/bin/python manage.py collectstatic --noinput -v 0
[ "${SKIP_SEED:-0}" = 1 ] || .venv/bin/python manage.py seed_demo
.venv/bin/python manage.py test --noinput -v 1 2>&1 | tail -3
ok "PostgreSQL 에서 테스트 완료"

# ---------------------------------------------------------------- 5. Gunicorn
step "5/7 Gunicorn 서비스 ($SERVICE)"
sudo tee /etc/systemd/system/$SERVICE.service >/dev/null <<UNIT
[Unit]
Description=Mister World Tour (Gunicorn)
After=network.target postgresql.service

[Service]
User=$APP_USER
Group=www-data
WorkingDirectory=$APP_DIR
RuntimeDirectory=$SERVICE
RuntimeDirectoryMode=0750
ExecStart=$APP_DIR/.venv/bin/gunicorn config.wsgi:application --workers 3 --timeout 60 \\
  --bind unix:/run/$SERVICE/gunicorn.sock --umask 007 --access-logfile - --error-logfile -
Restart=on-failure

[Install]
WantedBy=multi-user.target
UNIT
sudo systemctl daemon-reload
sudo systemctl enable --now $SERVICE >/dev/null
sudo systemctl restart $SERVICE
ok "실행 중: $(systemctl is-active $SERVICE)"

# ---------------------------------------------------------------- 6. Nginx
step "6/7 Nginx"
mkdir -p "$APP_DIR/media"
sudo chmod o+rx "$APP_DIR" "$APP_DIR/media"     # Nginx(www-data)가 정적 파일·업로드 사진을 읽을 수 있게
sudo tee /etc/nginx/sites-available/$SERVICE >/dev/null <<NGINX
server {
    listen 80;
    server_name $HOST $PUBLIC_IP;
    client_max_body_size 8M;                    # 사진 업로드(5MB) + 폼 여유

    location /static/ {
        alias $APP_DIR/staticfiles/;
        expires 7d;
    }

    location /media/ {                            # 직원이 올린 상품 사진
        alias $APP_DIR/media/;
        expires 7d;
    }

    location / {
        proxy_pass http://unix:/run/$SERVICE/gunicorn.sock;
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 60s;
    }
}
NGINX
sudo ln -sf /etc/nginx/sites-available/$SERVICE /etc/nginx/sites-enabled/$SERVICE
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t -q && sudo systemctl reload nginx
ok "http://$HOST"

# ---------------------------------------------------------------- 7. HTTPS
step "7/7 HTTPS 인증서"
SCHEME=http
if [ "${SKIP_HTTPS:-0}" = 1 ]; then
  warn "SKIP_HTTPS=1: 인증서 생략 (마이크 음성 선택은 HTTPS 에서만 동작)"
elif sudo certbot --nginx -d "$HOST" --non-interactive --agree-tos --register-unsafely-without-email \
       --redirect --keep-until-expiring -q; then
  SCHEME=https
  ok "인증서 발급 완료 (자동 갱신 타이머: certbot.timer)"
else
  warn "인증서 발급 실패: 보안 그룹에서 80·443 포트가 열려 있는지 확인 후 다시 실행하세요. 지금은 HTTP 로 동작합니다."
fi

if [ "$SCHEME" = https ]; then
  set_env DJANGO_HTTPS True
  set_env DJANGO_CSRF_TRUSTED_ORIGINS "https://$HOST"
else
  set_env DJANGO_HTTPS False
  set_env DJANGO_CSRF_TRUSTED_ORIGINS "http://$HOST,http://$PUBLIC_IP"
fi
sudo systemctl restart $SERVICE

code="$(curl -s -o /dev/null -w '%{http_code}' "$SCHEME://$HOST/")"
step "완료"
echo "    주소        : $SCHEME://$HOST/   (응답 $code)"
echo "    데모 계정   : staff1 / regular1 / customer2 / newbie  (비밀번호 demo1234!)"
echo "    직원 가입 코드: $(grep '^STAFF_SIGNUP_CODE=' .env | cut -d= -f2-)"
echo "    로그 보기   : sudo journalctl -u $SERVICE -f     (가짜 문자 발송 내용도 여기 표시)"
echo "    코드 갱신   : bash $APP_DIR/deploy/update.sh"
