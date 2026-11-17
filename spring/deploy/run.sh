#!/usr/bin/env bash
# 서버에서 Spring 판의 한 번 실행하고 끝나는 명령을 서비스와 같은 설정(prod·DB 이름)으로 실행한다.
#
#   bash /srv/mwt/spring/deploy/run.sh --complete-trips                    # 완료 여행 이력 일괄 반영
#   bash /srv/mwt/spring/deploy/run.sh --send-test-sms=010-1234-5678      # 문자 시험 발송
#
# 데모 데이터(--seed-demo)는 끝나도 서버가 계속 떠 있으므로 여기서 쓰지 않고 setup.sh 를 다시 실행한다.

set -euo pipefail
SPRING_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVICE=mwt-spring
cd "$SPRING_DIR"

# 서비스에 설정한 POSTGRES_DB 를 그대로 쓴다 (.env 의 값은 Django 판 DB)
DB_NAME="$(systemctl show -p Environment $SERVICE | grep -o 'POSTGRES_DB=[^ ]*' | cut -d= -f2)"
POSTGRES_DB="${DB_NAME:-mwt_spring}" exec java -jar run/mwt.jar --spring.profiles.active=prod \
  --server.port=0 --server.address=127.0.0.1 "$@"
