#!/usr/bin/env bash
# 서버의 코드를 최신으로 갱신하고 재시작한다.
#
#   bash /srv/mwt/deploy/update.sh            # 지금 브랜치의 최신 코드
#   BRANCH=main bash /srv/mwt/deploy/update.sh  # 다른 브랜치로 바꿔서 (PR 병합 후)

set -euo pipefail
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$APP_DIR"

if [ -n "${BRANCH:-}" ]; then
  git fetch -q origin "$BRANCH"
  git checkout -q "$BRANCH"
fi
git pull -q --ff-only
echo "==> $(git rev-parse --abbrev-ref HEAD) @ $(git log --oneline -1)"

.venv/bin/pip install -q -r requirements.txt
.venv/bin/python manage.py migrate --noinput
.venv/bin/python manage.py collectstatic --noinput -v 0
sudo systemctl restart mwt
echo "==> 재시작: $(systemctl is-active mwt)"
