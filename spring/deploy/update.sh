#!/usr/bin/env bash
# 서버의 Spring 판 코드를 최신으로 갱신하고 재시작한다. 표 변경은 시작할 때 Flyway 가 반영한다.
#
#   bash /srv/mwt/spring/deploy/update.sh               # 지금 브랜치의 최신 코드
#   BRANCH=main bash /srv/mwt/spring/deploy/update.sh   # 다른 브랜치로 바꿔서 (PR 병합 후)
#   RUN_TESTS=1 bash /srv/mwt/spring/deploy/update.sh   # 빌드 때 테스트도 실행

set -euo pipefail
SPRING_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVICE=mwt-spring
cd "$SPRING_DIR"

if [ -n "${BRANCH:-}" ]; then
  git fetch -q origin "$BRANCH"
  git checkout -q "$BRANCH"
fi
git pull -q --ff-only
echo "==> $(git rev-parse --abbrev-ref HEAD) @ $(git log --oneline -1)"

MVN_ARGS=(-q -B package)
[ "${RUN_TESTS:-0}" = 1 ] || MVN_ARGS+=(-DskipTests)
chmod +x mvnw
./mvnw "${MVN_ARGS[@]}"
mkdir -p run
BUILT="$(ls target/mwt-*.jar | grep -v plain | head -1)"
cp "$BUILT" run/mwt.jar.new && mv run/mwt.jar.new run/mwt.jar   # 실행 중인 jar 를 덮어쓰지 않고 바꿔치기

sudo systemctl restart $SERVICE
PORT="$(systemctl show -p Environment $SERVICE | grep -o 'PORT=[0-9]*' | cut -d= -f2)"
for _ in $(seq 1 90); do
  curl -fs -o /dev/null "http://127.0.0.1:${PORT:-8080}/" && break
  sleep 1
done
echo "==> 재시작: $(systemctl is-active $SERVICE)"
