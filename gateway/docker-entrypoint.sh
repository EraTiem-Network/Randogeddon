#!/bin/sh
# The gateway's start in its image (kotlin.Dockerfile, target gateway). docker-compose.yml starts it as root holding
# only CAP_SETUID, CAP_SETGID and CAP_KILL, with no-new-privileges. It becomes the gateway's own user (randogeddon, in
# Archipelago's group too) at once and keeps just those three, as ambient capabilities: the gateway starts each of
# Archipelago's processes as Archipelago's own user (archipelago: AP_RUN_AS) with every capability dropped (setpriv),
# and ends and pauses them across the two users (CAP_KILL). Another user can't read the gateway's environment (the
# database login, the internal token) or its memory. Started as any other user (docker run --user), the gateway runs as
# that one, and Archipelago's processes with it, as before.
set -eu

# What the gateway makes is its own and its group's: nobody else's, Archipelago's user's only where the gateway
# gives it (its group's folders, setgid)
umask 0007

if [ "$(id -u)" != 0 ]; then
  exec /app/randogeddon-gateway "$@"
fi

# Root without the three (a compose file of before, without cap_add): it couldn't become the gateway's user
caps=$(sed -n 's/^CapEff:[[:space:]]*//p' /proc/self/status)
# CAP_KILL (5), CAP_SETGID (6), CAP_SETUID (7)
if [ $((0x$caps & 0xe0)) -ne $((0xe0)) ]; then
  echo "The gateway's container needs cap_add: [SETUID, SETGID, KILL] (see docker-compose.yml)" >&2
  exit 1
fi

export AP_RUN_AS=archipelago HOME=/home/randogeddon
exec setpriv --reuid=randogeddon --regid=randogeddon --init-groups \
  --inh-caps=-all,+setuid,+setgid,+kill --ambient-caps=-all,+setuid,+setgid,+kill \
  -- /app/randogeddon-gateway "$@"
