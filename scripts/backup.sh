#!/usr/bin/env bash
# The live server's daily database dump (installed by scripts/deploy.sh as /usr/local/sbin/randogeddon-backup, run by
# randogeddon-backup.timer): /srv/backups/randogeddon-<time>-daily.sql.gz, the daily ones older than KEEP_DAYS deleted.
# The dumps before an update (…-before-<tag>.sql.gz) are kept as long as the daily ones. Restoring: README.
set -euo pipefail
KEEP_DAYS="${KEEP_DAYS:-14}"
cd /srv/randogeddon
mkdir -p /srv/backups
dump="/srv/backups/randogeddon-$(date +%Y%m%d-%H%M%S)-daily.sql.gz"
docker compose exec -T postgres pg_dump -U randogeddon randogeddon | gzip > "$dump.part"
mv "$dump.part" "$dump"
find /srv/backups -name 'randogeddon-*.sql.gz' -mtime +"$KEEP_DAYS" -delete
echo "Dumped to $dump ($(du -h "$dump" | cut -f1))"
