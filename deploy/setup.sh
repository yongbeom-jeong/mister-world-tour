#!/usr/bin/env bash
# 미스터 월드 투어 Spring Boot 판 서버 설치 (Ubuntu 24.04, AWS EC2)
#
#   bash /srv/mwt-spring/deploy/setup.sh
#
# 하는 일: 패키지(Java 21·PostgreSQL 18·Nginx) → .env(저장소 루트) → DB(mwt_spring)
#          → jar 빌드(테스트 포함) → Nginx → HTTPS 인증서 → systemd 서비스(mwt-spring) → 데모 데이터
# 여러 번 실행해도 안전하다 (이미 있는 .env · DB · 인증서는 그대로 사용).
# Django 판(deploy/setup.sh)이 같은 서버에 있어도 된다: DB·주소·서비스 이름이 모두 다르다.
#
# 환경변수로 바꿀 수 있는 값
#   HOST=example.com      사용할 도메인 (기본: spring.<공인IP>.sslip.io)
#   PORT=8080             Spring 이 듣는 포트 (127.0.0.1 에서만 열림, Nginx 가 프록시)
#   DB_NAME=mwt_spring    Spring 판 DB 이름 (Django 판 DB 와 섞지 않는다)
#   SKIP_HTTPS=1          인증서 발급 생략 (HTTP 만 사용)
#   SKIP_SEED=1           데모 데이터 생략
#   SKIP_TESTS=1          빌드 때 테스트 생략

set -euo pipefail

SPRING_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_DIR="$SPRING_DIR"                          # 저장소 루트 (.env 위치)
APP_USER="$(id -un)"
SERVICE=mwt-spring
DB_NAME="${DB_NAME:-mwt_spring}"
DB_USER=mwt
PG_VERSION=18
PORT="${PORT:-8080}"
JAR="$SPRING_DIR/run/mwt.jar"

step() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m    %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m    %s\033[0m\n' "$*"; }

cd "$SPRING_DIR"
[ -f mvnw ] && [ -f pom.xml ] || { echo "mvnw 가 없습니다. 저장소 안의 deploy/setup.sh 를 실행하세요."; exit 1; }

PUBLIC_IP="$(curl -fsS https://checkip.amazonaws.com | tr -d '[:space:]')"
HOST="${HOST:-spring.${PUBLIC_IP//./-}.sslip.io}"
step "설치 위치 $SPRING_DIR · 사용자 $APP_USER · 공인 IP $PUBLIC_IP · 주소 $HOST"

# ---------------------------------------------------------------- 1. 패키지
step "1/7 패키지 설치 (Java 21 · PostgreSQL $PG_VERSION · Nginx)"
export DEBIAN_FRONTEND=noninteractive
sudo apt-get update -y -q
sudo apt-get install -y -q openjdk-21-jdk-headless git curl openssl nginx postgresql-common \
  certbot python3-certbot-nginx
if ! dpkg -s "postgresql-$PG_VERSION" >/dev/null 2>&1; then
  sudo /usr/share/postgresql-common/pgdg/apt.postgresql.org.sh -y   # PostgreSQL 공식 저장소
  sudo apt-get install -y -q "postgresql-$PG_VERSION"
fi
ok "$(java -version 2>&1 | head -1)"
ok "PostgreSQL $(sudo -u postgres psql -tAc 'SHOW server_version')"

# 메모리가 작은 인스턴스에서 Maven 빌드가 멈추지 않도록 스왑 1GB
if [ "$(swapon --show | wc -l)" -eq 0 ]; then
  sudo fallocate -l 1G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile >/dev/null && sudo swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
  ok "스왑 1GB 추가"
fi

# ---------------------------------------------------------------- 2. .env
step "2/7 .env (저장소 루트, Django 판과 같은 파일. 비밀값은 서버에서 무작위 생성)"
ENV_FILE="$APP_DIR/.env"
set_env() {  # set_env KEY VALUE : .env 의 값을 바꾸거나 추가 (.env 가 Django 판 파일로의 링크여도 원본을 고친다)
  if grep -q "^$1=" "$ENV_FILE"; then sed -i --follow-symlinks "s|^$1=.*|$1=$2|" "$ENV_FILE"; else echo "$1=$2" >> "$ENV_FILE"; fi
}
if [ ! -f "$ENV_FILE" ]; then
  cp "$APP_DIR/.env.example" "$ENV_FILE"
  set_env DJANGO_SECRET_KEY "$(openssl rand -base64 48 | tr -d '\n/+=')"
  set_env POSTGRES_PASSWORD "$(openssl rand -hex 16)"
  set_env STAFF_SIGNUP_CODE "MWT-STAFF-$(openssl rand -hex 3 | tr a-f A-F)"
  ok ".env 새로 만듦"
else
  ok "기존 .env 사용 (POSTGRES_DB 는 Django 판 값 그대로 두고, Spring 은 서비스에서 '$DB_NAME' 로 덮어쓴다)"
fi
grep -q '^POSTGRES_PASSWORD=..*' "$ENV_FILE" || set_env POSTGRES_PASSWORD "$(openssl rand -hex 16)"
set_env POSTGRES_USER "$DB_USER"
set_env POSTGRES_HOST 127.0.0.1
set_env POSTGRES_PORT 5432
DB_PASS="$(grep '^POSTGRES_PASSWORD=' "$ENV_FILE" | cut -d= -f2-)"
chmod 600 "$ENV_FILE"

# ---------------------------------------------------------------- 3. DB
step "3/7 데이터베이스 (표는 앱이 시작할 때 Flyway 가 만든다)"
if sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='$DB_USER'" | grep -q 1; then
  sudo -u postgres psql -q -c "ALTER ROLE $DB_USER WITH LOGIN CREATEDB PASSWORD '$DB_PASS'"
else
  sudo -u postgres psql -q -c "CREATE ROLE $DB_USER WITH LOGIN CREATEDB PASSWORD '$DB_PASS'"
fi
if ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='$DB_NAME'" | grep -q 1; then
  sudo -u postgres createdb -O "$DB_USER" -E UTF8 -T template0 "$DB_NAME"
fi
ok "DB '$DB_NAME' / 사용자 '$DB_USER'"

# ---------------------------------------------------------------- 4. 빌드
step "4/7 jar 빌드 (mvnw package$( [ "${SKIP_TESTS:-0}" = 1 ] && echo ', 테스트 생략' ))"
chmod +x mvnw
MVN_ARGS=(-q -B package)
[ "${SKIP_TESTS:-0}" = 1 ] && MVN_ARGS+=(-DskipTests)
./mvnw "${MVN_ARGS[@]}"
mkdir -p run media
BUILT="$(ls target/mwt-*.jar | grep -v plain | head -1)"
cp "$BUILT" run/mwt.jar.new && mv run/mwt.jar.new "$JAR"   # 새 파일로 바꿔치기 (실행 중인 jar 를 덮어쓰지 않음)
ok "$JAR ($(du -h "$JAR" | cut -f1))"

# ---------------------------------------------------------------- 5. Nginx
step "5/7 Nginx"
sudo chmod o+x "$APP_DIR" "$SPRING_DIR"
sudo chmod o+rx "$SPRING_DIR/media"            # Nginx(www-data)가 업로드 사진을 읽을 수 있게
SERVER_NAMES="$HOST"
# Django 판 사이트가 없을 때만 IP 주소로 들어와도 Spring 판을 보여 준다
[ -e /etc/nginx/sites-enabled/mwt ] || SERVER_NAMES="$HOST $PUBLIC_IP"
sudo tee /etc/nginx/sites-available/$SERVICE >/dev/null <<NGINX
server {
    listen 80;
    server_name $SERVER_NAMES;
    client_max_body_size 8M;                    # 사진 업로드(5MB) + 폼 여유

    location /media/ {                            # 직원이 올린 상품 사진 (mwt.media-root)
        alias $SPRING_DIR/media/;
        expires 7d;
    }

    location / {                                  # CSS·JS·기본 사진도 jar 안에서 Spring 이 제공
        proxy_pass http://127.0.0.1:$PORT;
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 60s;                   # 문자 묶음 한도(40초)보다 길게
    }
}
NGINX
sudo ln -sf /etc/nginx/sites-available/$SERVICE /etc/nginx/sites-enabled/$SERVICE
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t -q && sudo systemctl reload nginx
ok "http://$HOST"

# ---------------------------------------------------------------- 6. HTTPS
step "6/7 HTTPS 인증서"
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
COOKIE_SECURE=false
[ "$SCHEME" = https ] && COOKIE_SECURE=true   # HTTP 로만 쓰면 보안 쿠키를 끈다 (안 그러면 로그인이 유지되지 않음)

# ---------------------------------------------------------------- 7. 서비스 · 데모 데이터
step "7/7 systemd 서비스 ($SERVICE) · 데모 데이터"
# 환경변수는 .env 보다 우선한다: DB 이름·포트만 여기서 정하고, 나머지는 .env 를 그대로 읽는다
sudo tee /etc/systemd/system/$SERVICE.service >/dev/null <<UNIT
[Unit]
Description=Mister World Tour (Spring Boot)
After=network.target postgresql.service

[Service]
User=$APP_USER
WorkingDirectory=$SPRING_DIR
Environment=POSTGRES_DB=$DB_NAME
Environment=PORT=$PORT
Environment=SERVER_ADDRESS=127.0.0.1
Environment=SERVER_SERVLET_SESSION_COOKIE_SECURE=$COOKIE_SECURE
Environment="JAVA_OPTS=${JAVA_OPTS:--Xms128m -Xmx512m}"
ExecStart=/usr/bin/java \$JAVA_OPTS -jar $JAR --spring.profiles.active=prod
SuccessExitStatus=143
Restart=on-failure

[Install]
WantedBy=multi-user.target
UNIT
sudo systemctl daemon-reload
sudo systemctl enable $SERVICE >/dev/null

if [ "${SKIP_SEED:-0}" != 1 ]; then
  # --seed-demo 는 서버를 띄운 채 데이터를 넣으므로, 빈 포트로 잠깐 띄웠다가 완료 로그를 보고 끈다
  sudo systemctl stop $SERVICE || true
  SEED_LOG="$(mktemp)"
  POSTGRES_DB="$DB_NAME" java -jar "$JAR" --spring.profiles.active=prod --server.port=0 \
    --server.address=127.0.0.1 --seed-demo >"$SEED_LOG" 2>&1 &
  SEED_PID=$!
  for _ in $(seq 1 180); do
    grep -q '데모 데이터 준비 완료' "$SEED_LOG" && break
    kill -0 "$SEED_PID" 2>/dev/null || break
    sleep 1
  done
  if grep -q '데모 데이터 준비 완료' "$SEED_LOG"; then
    ok "$(grep '데모 데이터 준비 완료' "$SEED_LOG" | sed 's/.*: 데모/데모/')"
  else
    warn "데모 데이터 확인 실패. 마지막 로그:"; tail -20 "$SEED_LOG"
  fi
  kill "$SEED_PID" 2>/dev/null || true; wait "$SEED_PID" 2>/dev/null || true
  rm -f "$SEED_LOG"
fi

sudo systemctl restart $SERVICE
for _ in $(seq 1 90); do
  curl -fs -o /dev/null "http://127.0.0.1:$PORT/" && break
  sleep 1
done
ok "실행 중: $(systemctl is-active $SERVICE)"

code="$(curl -s -o /dev/null -w '%{http_code}' "$SCHEME://$HOST/")"
step "완료"
echo "    주소          : $SCHEME://$HOST/   (응답 $code)"
echo "    데모 계정     : staff1 / regular1 / customer2 / newbie  (비밀번호 demo1234!)"
echo "    직원 가입 코드: $(grep '^STAFF_SIGNUP_CODE=' "$ENV_FILE" | cut -d= -f2-)"
echo "    로그 보기     : sudo journalctl -u $SERVICE -f     (가짜 문자 발송 내용도 여기 표시)"
echo "    코드 갱신     : bash $SPRING_DIR/deploy/update.sh"
