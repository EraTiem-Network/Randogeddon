#!/bin/sh
# A hosted Minecraft server's Java (the minecraft image's JAVA: minecraft_host.py starts it in the server's folder): as
# the servers' own user (minecraft-server, uid 1200, in randogeddon's group for the servers' folders), which can't read
# minecraft_host.py's environment (MINECRAFT_TOKEN) or signal it, with no capability left, at most 1536 processes and
# threads of that user at once (the container has 2048) and no file larger than 4 GiB. Without the capabilities to
# switch (started as another user, see minecraft-entrypoint.sh): as the host's own user, as before.
caps=$(sed -n 's/^CapEff:[[:space:]]*//p' /proc/self/status)
# CAP_SETGID (6), CAP_SETUID (7)
if [ $((0x$caps & 0xc0)) -ne $((0xc0)) ]; then
  exec java "$@"
fi
HOME="$PWD" exec setpriv --reuid=minecraft-server --regid=randogeddon --clear-groups --inh-caps=-all \
  --ambient-caps=-all --no-new-privs -- prlimit --nproc=1536 --fsize=4294967296 -- java "$@"
