#!/usr/bin/env bash
# The live server's daily database dump (installed by scripts/deploy.sh as /usr/local/sbin/randogeddon-backup, run by
# randogeddon-backup.timer): /srv/backups/randogeddon-<time>-daily.sql.gz, the daily ones older than KEEP_DAYS deleted.
# The dumps before an update (…-before-<tag>.sql.gz) are kept as long as the daily ones. Restoring: .docs/OPERATIONS.md.
set -euo pipefail
KEEP_DAYS="${KEEP_DAYS:-14}"
cd /srv/randogeddon
# The dumps hold password hashes, addresses and the slot passwords: for root only
umask 077
mkdir -p /srv/backups
chmod 700 /srv/backups
dump="/srv/backups/randogeddon-$(date +%Y%m%d-%H%M%S)-daily.sql.gz"
# Written under a temporary name, which goes whatever happens; the dump's own name only once it is whole. Without
# owners and rights: the roles and their rights are made at every start, so a restore that stops at the first error
# works on a server whose roles are named otherwise too
trap 'rm -f "$dump.part"' EXIT
if ! docker compose exec -T postgres pg_dump -U randogeddon --no-owner --no-privileges randogeddon </dev/null \
  | gzip > "$dump.part"; then
  echo "The dump failed (pg_dump or gzip): nothing kept" >&2
  exit 1
fi
# Whole: a sound gzip, and pg_dump's own last lines are in it (a dump cut off anywhere lacks them)
if [[ ! -s "$dump.part" ]] || ! gzip -t "$dump.part" \
  || [[ "$(gzip -dc "$dump.part" | tail -n 10)" != *"-- PostgreSQL database dump complete"* ]]; then
  echo "The dump isn't whole: nothing kept" >&2
  exit 1
fi
mv "$dump.part" "$dump"
# A run that was killed outright (no trap) leaves its .part: gone after a day
find /srv/backups -name 'randogeddon-*.sql.gz.part' -mtime +0 -delete
find /srv/backups -name 'randogeddon-*.sql.gz' -mtime +"$KEEP_DAYS" -delete
echo "Dumped to $dump ($(du -h "$dump" | cut -f1))"
