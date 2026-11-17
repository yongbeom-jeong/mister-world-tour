#!/usr/bin/env bash
# 미스터 월드 투어 서버 백업 (계획서 4장 위험 분석: 데이터베이스와 설정 파일 정기 백업)
#
# setup.sh 가 /usr/local/bin/mwt-backup 으로 설치하고 systemd 타이머(mwt-backup.timer)가 매일 04:00(KST) 실행한다.
# 직접 실행: sudo /usr/local/bin/mwt-backup
#
# 남기는 것 (/var/backups/mwt, 기본 14일 보관)
#   db-<DB>-<시각>.dump      PostgreSQL 덤프 (pg_dump -Fc). 파일은 root 전용이라 내용을 넘겨 복원한다:
#                            sudo cat <파일> | sudo -u postgres pg_restore -c -d <DB>
#   files-<시각>.tar.gz      .env(비밀값) + 업로드 사진(media/)
#
# 환경변수: APP_DIR(저장소 위치) · DB_NAME · BACKUP_DIR · KEEP_DAYS
# 백업은 같은 서버 디스크에 남는다. 인스턴스 자체를 잃는 경우에는 EBS 스냅샷이나 PC 로 내려받기가 따로 필요하다.

set -euo pipefail

APP_DIR="${APP_DIR:-/srv/mwt-spring}"
DB_NAME="${DB_NAME:-mwt_spring}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/mwt}"
KEEP_DAYS="${KEEP_DAYS:-14}"
STAMP="$(TZ=Asia/Seoul date +%Y%m%d-%H%M)"

umask 077                                   # 비밀값이 들어 있으므로 root 만 읽을 수 있게
mkdir -p "$BACKUP_DIR"

DB_FILE="$BACKUP_DIR/db-$DB_NAME-$STAMP.dump"
sudo -u postgres pg_dump -Fc "$DB_NAME" > "$DB_FILE.part"
mv "$DB_FILE.part" "$DB_FILE"

FILES=()
[ -e "$APP_DIR/.env" ] && FILES+=(.env)
[ -d "$APP_DIR/media" ] && FILES+=(media)
if [ "${#FILES[@]}" -gt 0 ]; then
  tar -czhf "$BACKUP_DIR/files-$STAMP.tar.gz" -C "$APP_DIR" "${FILES[@]}"
fi

find "$BACKUP_DIR" -maxdepth 1 -type f \( -name 'db-*.dump' -o -name 'files-*.tar.gz' \) -mtime +"$KEEP_DAYS" -delete

echo "백업 완료: $DB_FILE ($(du -h "$DB_FILE" | cut -f1)), 보관 $KEEP_DAYS일"
ls -1t "$BACKUP_DIR" | head -4
