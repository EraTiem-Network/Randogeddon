#!/bin/sh
# The minecraft service's start (kotlin.Dockerfile, target minecraft; kept beside the gateway's start, whose pattern it
# follows). docker-compose.yml starts it as root holding only CAP_SETUID, CAP_SETGID and CAP_KILL, with
# no-new-privileges. It becomes randogeddon (uid 1000, the data folder's owner) at once, keeping those three as ambient
# capabilities: minecraft_host.py starts each server's Java through java-as-server, which drops to the servers' own
# user (minecraft-server) with nothing left, and ends servers across the two users (CAP_KILL). Started as any other
# user (a compose file of before, with user "1000"), everything runs as that one, as before.
set -eu

# What is made is its owner's and its group's (randogeddon's group: the servers' user writes the servers' folders
# through it), nobody else's
umask 0007

if [ "$(id -u)" = 0 ]; then
  caps=$(sed -n 's/^CapEff:[[:space:]]*//p' /proc/self/status)
  # CAP_KILL (5), CAP_SETGID (6), CAP_SETUID (7)
  if [ $((0x$caps & 0xe0)) -ne $((0xe0)) ]; then
    echo "The minecraft container needs cap_add: [SETUID, SETGID, KILL] (see docker-compose.yml)" >&2
    exit 1
  fi
  export HOME=/home/randogeddon
  exec setpriv --reuid=randogeddon --regid=randogeddon --init-groups \
    --inh-caps=-all,+setuid,+setgid,+kill --ambient-caps=-all,+setuid,+setgid,+kill -- "$0" "$@"
fi

# The servers' folders made before the servers had a user of their own: their group's to write too (only randogeddon's
# own entries; links are left as they are)
servers="${DATA:-/data}/servers"
if [ -d "$servers" ]; then
  find -P "$servers" -user "$(id -u)" \( \( -type d ! -perm -0070 \) -o \( -type f ! -perm -0060 \) \) \
    -exec chmod g+rwX {} + || true
fi

exec python3 /app/minecraft_host.py "$@"
