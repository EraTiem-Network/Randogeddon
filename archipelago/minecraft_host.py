"""The hosted Minecraft servers of Minecraft Dig slots, run on the gateway's orders.

Usage: python minecraft_host.py (its own service and image, with none of the gateway's secrets: only MINECRAFT_TOKEN,
which it shares with the gateway and which opens nothing but this API; DATA is the only folder it has)

One Forge server per Minecraft Dig slot, each a process with its own folder under DATA/servers/<key>/ (its world,
its config; the Forge libraries and the mod are the image's, linked in). Everything of the seed it needs comes in the
gateway's request (the slot's patch). The gateway asks over HTTP, with `Authorization: Bearer <MINECRAFT_TOKEN>`:
    GET    /servers                     every server: its state, its Minecraft port, its players
    PUT    /servers/<key>               its files for a seed: {"session", "slot", "seed", "data": <the patch's
                                        data.json>, "eula": true}; a world of another seed is dropped
    POST   /servers/<key>/start         started (its world kept while it sleeps)
    POST   /servers/<key>/stop          stopped (`stop` in its console, killed if it doesn't end)
    POST   /servers/<key>/command       {"line": ...} into its console
    GET    /servers/<key>/log?since=N   its lines numbered above N
    DELETE /servers/<key>               stopped and its folder removed (its session is gone)

The mod connects to Archipelago by `/connect <address> <port> "<password>"` and builds its address as
`ws://<address>:<port>`, without a path, while the gateway tells sessions apart by theirs (`/<session>`). So each
server has a relay on 127.0.0.1: the mod connects there, and the relay passes the connection on to the gateway with
the session's path in its WebSocket request.

No server takes more than LIMIT_MB: a watchdog ends one that does (`-Xmx` bounds only its heap). One server's
requests are done one at a time (its files aren't changed under it, it isn't started twice); its files can't be
changed while it runs (a PUT for its own seed then changes nothing). The API takes at most MAX_CONNECTIONS at once,
each for at most REQUEST_SECONDS of silence.

The Java servers run as this service's user: the token is kept out of their environment, but a mod's code could still
read it in this process's (/proc/<pid>/environ of the same user). Only a second user for the servers would keep it
from them (the image's and docker-compose.yml's matter).
"""
import base64
import collections
import hmac
import json
import os
import re
import shutil
import signal
import socket
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

# Its own, never the gateway's INTERNAL_TOKEN; without it every call is refused
TOKEN = os.environ.get("MINECRAFT_TOKEN", "")
DATA = os.environ.get("DATA", "/data")
FORGE = os.environ.get("FORGE_HOME", "/opt/forge")
FORGE_VERSION = os.environ.get("FORGE_VERSION", "1.19.4-45.3.15")
JAVA = os.environ.get("JAVA", "java")
HEAP = os.environ.get("MINECRAFT_HEAP", "2G")
LIMIT_MB = int(os.environ.get("MINECRAFT_LIMIT_MB", "3072"))
# How many may run at once (the rest are refused until one stops)
AT_ONCE = int(os.environ.get("MINECRAFT_SERVERS", "1"))
GATEWAY = os.environ.get("GATEWAY_WS", "gateway:38281")
API_PORT = int(os.environ.get("MINECRAFT_API_PORT", "8002"))
# Minecraft's ports, and the relays' (each server one of each, by its number)
FIRST_PORT = 25600
FIRST_RELAY = 39000
LOG_LINES = 5000
# The most a request's body may have: the patch's data.json is at most 1 MB (the gateway reads no longer one)
MAX_BODY = 4 * 1024 * 1024
KEY = re.compile(r"^[a-z0-9][a-z0-9-]{0,80}$")
# A session's ID, as the gateway makes them
SESSION = re.compile(r"^[a-z][a-z0-9-]{1,38}[a-z0-9]$")
# Control characters: none in a slot's name (a file name, a line of server.properties)
CONTROL = re.compile(r"[\x00-\x1f\x7f]")
# A player joins or leaves: the server's own line ("[12:00:00] [Server thread/INFO] [minecraft/MinecraftServer]: Steve
# joined the game"), from its start; a chat line has "<Name> " right after the first "]: ", so its text can't match
JOINED = re.compile(r"^\[[^\]]*\] \[[^\]]*/INFO\](?: \[[^\]]*\])*: ([A-Za-z0-9_]{1,16}) joined the game$")
LEFT = re.compile(r"^\[[^\]]*\] \[[^\]]*/INFO\](?: \[[^\]]*\])*: ([A-Za-z0-9_]{1,16}) left the game$")
# The gateway's console line that connects the mod to Archipelago, with the slot's password in double quotes
CONNECT = re.compile(r'^\s*/?connect\s+\S+\s+\d+\s+"([^"]+)"\s*$')
# The API: how many requests at once (one more is answered 503), and how long a connection may be silent
MAX_CONNECTIONS = 64
REQUEST_SECONDS = 30
# How long a relay waits for the mod's first bytes
RELAY_FIRST_SECONDS = 30

servers = {}
lock = threading.Lock()
# Held while a server is started: how many run is counted and the new one started at once
starts = threading.Lock()


class Server:
    def __init__(self, key: str, number: int):
        self.key = key
        self.number = number
        self.dir = os.path.join(DATA, "servers", key)
        self.process = None
        self.status = "stopped"
        self.since = None
        self.exit_code = None
        self.players = set()
        # When a player was last on it (epoch ms; its start counts): kept here, where it runs, so the gateway's idle
        # time goes on across the gateway's restarts
        self.last_player = None
        self.session = None
        self.lines = collections.deque(maxlen=LOG_LINES)
        self.last = 0
        self.stopping = False
        # The slot's password it was last given (the `connect` line): never in its log, whatever echoes it
        self.secret = None
        # One request of this server's at a time (prepare, start, delete)
        self.guard = threading.RLock()
        self.read_meta()

    @property
    def port(self) -> int:
        return FIRST_PORT + self.number

    @property
    def relay(self) -> int:
        return FIRST_RELAY + self.number

    def note(self, text: str) -> None:
        if self.secret:
            text = text.replace(self.secret, "***")
        with lock:
            self.last += 1
            self.lines.append({"n": self.last, "ts": int(time.time() * 1000), "text": text[:2000]})

    def read_meta(self) -> None:
        try:
            with open(os.path.join(self.dir, "randogeddon.json"), encoding="utf-8") as f:
                session = json.load(f).get("session")
        except (OSError, ValueError, AttributeError):
            return
        # It goes into the relay's request line: only a session's ID
        self.session = session if isinstance(session, str) and SESSION.fullmatch(session) else None

    def running(self) -> bool:
        return bool(self.process and self.process.poll() is None)

    def view(self) -> dict:
        return {"key": self.key, "status": self.status, "since": self.since, "port": self.port, "relay": self.relay,
                "players": sorted(self.players), "lastPlayer": self.last_player, "exitCode": self.exit_code,
                "session": self.session}


def number_for(key: str) -> int:
    taken = {s.number for s in servers.values()}
    return next(n for n in range(1000) if n not in taken)


def server(key: str, create: bool = False):
    with lock:
        if key not in servers and create:
            servers[key] = Server(key, number_for(key))
        return servers.get(key)


def checked(key: str, body) -> dict:
    """A PUT's [body] for server [key], if it holds what prepare() needs and names nothing outside the server's folder;
    else a ValueError. The folder and the relay's path come from the key and the session, so the key must be the
    session's (`<session>-p<number>`, as the gateway makes it); the slot's name becomes a file name in APData/."""
    if not isinstance(body, dict):
        raise ValueError("not a JSON object")
    session, slot, seed, data = (body.get(k) for k in ("session", "slot", "seed", "data"))
    if not all(isinstance(v, str) for v in (session, slot, seed, data)):
        raise ValueError("session, slot, seed and data are needed")
    if not SESSION.fullmatch(session) or not re.fullmatch(re.escape(session) + r"-p\d+", key):
        raise ValueError("not this server's session")
    if not slot or len(slot) > 64 or slot in (".", "..") or "/" in slot or "\\" in slot or CONTROL.search(slot):
        raise ValueError("not a slot's name")
    return body


def prepare(s: Server, body: dict) -> None:
    """Its folder for [body]'s seed (checked()): the image's Forge linked in, its settings, the slot's patch."""
    os.makedirs(s.dir, exist_ok=True)
    meta_path = os.path.join(s.dir, "randogeddon.json")
    try:
        with open(meta_path, encoding="utf-8") as f:
            before = json.load(f)
    except (OSError, ValueError):
        before = {}
    # A world is made for one seed: another seed gets a fresh one
    if before.get("seed") not in (None, body["seed"]):
        shutil.rmtree(os.path.join(s.dir, "world"), ignore_errors=True)
        s.note("[host] Another seed: its world starts fresh")
    for name in ("libraries", "mods"):
        link = os.path.join(s.dir, name)
        if not os.path.islink(link):
            shutil.rmtree(link, ignore_errors=True)
            os.symlink(os.path.join(FORGE, name), link)
    apdata = os.path.join(s.dir, "APData")
    shutil.rmtree(apdata, ignore_errors=True)
    os.makedirs(apdata)
    # As the APWorld's client writes it: the patch's data.json in base64. No archipelago.json: the mod would connect
    # without a password; it connects when the gateway says so
    with open(os.path.join(apdata, f"{body['slot']}.apmc"), "w", encoding="utf-8") as f:
        f.write(base64.b64encode(body["data"].encode("utf-8")).decode("ascii"))
    with open(os.path.join(s.dir, "eula.txt"), "w", encoding="utf-8") as f:
        f.write(f"eula={'true' if body.get('eula') else 'false'}\n")
    properties = {
        "server-port": s.port, "server-ip": "", "online-mode": "true", "enforce-secure-profile": "true",
        "motd": f"Randogeddon: {body['slot']}", "white-list": "false", "enable-rcon": "false",
        "enable-query": "false", "spawn-protection": "0", "max-players": "20",
    }
    with open(os.path.join(s.dir, "server.properties"), "w", encoding="utf-8") as f:
        f.writelines(f"{k}={v}\n" for k, v in properties.items())
    with open(meta_path, "w", encoding="utf-8") as f:
        json.dump({"session": body["session"], "slot": body["slot"], "seed": body["seed"]}, f)
    s.session = body["session"]


def same_files(s: Server, body: dict) -> bool:
    """Whether [s]'s files are those of [body] already (its session, slot and seed)."""
    try:
        with open(os.path.join(s.dir, "randogeddon.json"), encoding="utf-8") as f:
            meta = json.load(f)
    except (OSError, ValueError):
        return False
    return isinstance(meta, dict) and all(meta.get(k) == body[k] for k in ("session", "slot", "seed"))


def start(s: Server) -> str:
    """Started; an empty answer, or why not."""
    with s.guard:
        if s.running():
            return ""
        if not os.path.isfile(os.path.join(s.dir, "randogeddon.json")):
            return "it has no files yet"
        with open(os.path.join(s.dir, "eula.txt"), encoding="utf-8") as f:
            if "eula=true" not in f.read():
                return "Mojang's EULA isn't accepted (an Administrator's setting)"
        with starts:
            with lock:
                others = list(servers.values())
            running = sum(1 for o in others if o.running())
            if running >= AT_ONCE:
                return f"{AT_ONCE} Minecraft server(s) run already, the most at once"
            args = f"@libraries/net/minecraftforge/forge/{FORGE_VERSION}/unix_args.txt"
            s.stopping = False
            s.players = set()
            s.exit_code = None
            s.status = "starting"
            s.since = int(time.time() * 1000)
            s.last_player = s.since
            s.note(f"[host] Starting: {JAVA} -Xmx{HEAP} {args} -nogui")
            # Without the tokens in its environment (see the module's description: that doesn't hide them from it)
            s.process = subprocess.Popen(
                [JAVA, f"-Xmx{HEAP}", args, "-nogui"], cwd=s.dir, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT, text=True, bufsize=1,
                env={k: v for k, v in os.environ.items() if k not in ("MINECRAFT_TOKEN", "INTERNAL_TOKEN")},
            )
        threading.Thread(target=read_output, args=(s, s.process), daemon=True).start()
        return ""


def read_output(s: Server, process) -> None:
    for line in process.stdout:
        line = line.rstrip("\r\n")
        s.note(line)
        if s.status == "starting" and "Done (" in line:
            s.status = "running"
            s.since = int(time.time() * 1000)
        joined = JOINED.match(line)
        if joined:
            s.players.add(joined.group(1))
            s.last_player = int(time.time() * 1000)
        left = LEFT.match(line)
        if left:
            s.players.discard(left.group(1))
            s.last_player = int(time.time() * 1000)
    code = process.wait()
    s.exit_code = code
    s.players = set()
    s.status = "stopped" if s.stopping else "crashed"
    s.since = int(time.time() * 1000)
    s.note(f"[host] Ended with exit code {code}")


def stop(s: Server, wait: float = 45) -> None:
    process = s.process
    if not process or process.poll() is not None:
        return
    s.stopping = True
    command(s, "stop")
    try:
        process.wait(wait)
    except subprocess.TimeoutExpired:
        process.kill()
        s.note("[host] Killed: it didn't stop in time")


def command(s: Server, line: str) -> bool:
    """[line] into [s]'s console, as one line (Java ends a line at "\\r" too); whether it runs. A slot's password it
    gives (`connect`) is kept out of its log from now on."""
    process = s.process
    if not process or process.poll() is not None:
        return False
    line = CONTROL.sub(" ", line)
    connect = CONNECT.match(line)
    if connect:
        s.secret = connect.group(1)
    try:
        process.stdin.write(line + "\n")
        process.stdin.flush()
        return True
    except OSError:
        return False


def watchdog() -> None:
    """Ends a server that takes more than LIMIT_MB."""
    while True:
        time.sleep(5)
        with lock:
            all_servers = list(servers.values())
        for s in all_servers:
            process = s.process
            if not process or process.poll() is not None:
                continue
            try:
                with open(f"/proc/{process.pid}/status") as f:
                    rss = next(int(l.split()[1]) // 1024 for l in f if l.startswith("VmRSS"))
            except (OSError, StopIteration):
                continue
            if rss > LIMIT_MB:
                s.note(f"[host] Killed: it took {rss} MB, more than {LIMIT_MB} MB")
                process.kill()


def relay(s: Server, listener) -> None:
    """127.0.0.1:<relay> to the gateway, with the session's path put into the WebSocket request; until it is closed."""
    while True:
        try:
            client, _ = listener.accept()
        except OSError:
            return
        threading.Thread(target=relay_one, args=(s, client), daemon=True).start()


def relay_one(s: Server, client) -> None:
    try:
        # A connection that sends nothing doesn't hold its thread for good
        client.settimeout(RELAY_FIRST_SECONDS)
        first = client.recv(65536)
        # Only a WebSocket request (the mod may try TLS first: that is refused, and it tries without)
        if not first.startswith(b"GET ") or not s.session:
            client.close()
            return
        line_end = first.index(b"\r\n")
        method, _, rest = first[:line_end].split(b" ", 2)
        first = method + b" /" + s.session.encode() + b" " + rest + first[line_end:]
        host, port = GATEWAY.rsplit(":", 1)
        upstream = socket.create_connection((host, int(port)), timeout=RELAY_FIRST_SECONDS)
        upstream.sendall(first)
        # From now on a WebSocket, which may be quiet for long
        client.settimeout(None)
        upstream.settimeout(None)
    except (OSError, ValueError):
        client.close()
        return

    def pipe(a, b):
        try:
            while data := a.recv(65536):
                b.sendall(data)
        except OSError:
            pass
        finally:
            for c in (a, b):
                try:
                    c.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass

    threading.Thread(target=pipe, args=(upstream, client), daemon=True).start()
    pipe(client, upstream)


# Each server's relay's listening socket, by key
relays = {}


def ensure_relay(s: Server) -> None:
    with lock:
        if s.key in relays:
            return
        listener = socket.socket()
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            listener.bind(("127.0.0.1", s.relay))
            listener.listen()
            relays[s.key] = listener
            why = None
        except OSError as e:
            listener.close()
            why = str(e)
    if why:
        s.note(f"[host] Its relay on {s.relay} didn't open: {why}")
        return
    threading.Thread(target=relay, args=(s, listener), daemon=True).start()


def close_relay(key: str) -> None:
    """A deleted server's relay ends: its port is free for the next server, which may have another session."""
    with lock:
        listener = relays.pop(key, None)
    if listener is not None:
        try:
            listener.shutdown(socket.SHUT_RDWR)  # wakes its accept()
        except OSError:
            pass
        listener.close()


class Handler(BaseHTTPRequestHandler):
    # A connection silent this long is closed (its socket's timeout)
    timeout = REQUEST_SECONDS

    def log_message(self, *_):
        pass

    def answer(self, status: int, body=None) -> None:
        data = b"" if body is None else json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def allowed(self) -> bool:
        given = self.headers.get("Authorization", "").removeprefix("Bearer ").encode()
        if not TOKEN or not hmac.compare_digest(given, TOKEN.encode()):
            self.answer(401)
            return False
        return True

    def target(self):
        parts = urlparse(self.path).path.strip("/").split("/")
        key = parts[1] if len(parts) > 1 else None
        if parts[0] != "servers" or (key is not None and not KEY.match(key)):
            self.answer(404)
            return None, None, None
        return parts, key, (parts[2] if len(parts) > 2 else None)

    def body(self):
        """The request's JSON; a ValueError if it is longer than MAX_BODY, or no JSON."""
        length = int(self.headers.get("Content-Length", "0"))
        if not 0 <= length <= MAX_BODY:
            raise ValueError(f"a body of {length} bytes")
        return json.loads(self.rfile.read(length) or b"{}")

    def do_GET(self):
        if not self.allowed():
            return
        parts, key, action = self.target()
        if parts is None:
            return
        if key is None:
            with lock:
                all_servers = list(servers.values())
            return self.answer(200, {"servers": [s.view() for s in all_servers]})
        s = server(key)
        if s is None:
            return self.answer(404)
        if action == "log":
            try:
                since = int(parse_qs(urlparse(self.path).query).get("since", ["0"])[0])
            except ValueError:
                return self.answer(400, {"message": "since isn't a number"})
            with lock:
                lines = [l for l in s.lines if l["n"] > since][:1000]
            return self.answer(200, {"lines": lines, "last": lines[-1]["n"] if lines else max(since, s.last)})
        return self.answer(200, s.view())

    def do_PUT(self):
        if not self.allowed():
            return
        parts, key, _ = self.target()
        if parts is None or key is None:
            return
        try:
            body = checked(key, self.body())
        except ValueError as e:
            return self.answer(400, {"message": str(e)})
        s = server(key, create=True)
        with s.guard:
            if s.running():
                # Its files aren't changed under it: for its own seed there is nothing to change
                if same_files(s, body):
                    return self.answer(200, s.view())
                return self.answer(409, {"message": "it runs: its files can't be changed now"})
            prepare(s, body)
        ensure_relay(s)
        self.answer(200, s.view())

    def do_POST(self):
        if not self.allowed():
            return
        parts, key, action = self.target()
        if parts is None or key is None:
            return
        s = server(key)
        if s is None:
            return self.answer(404)
        if action == "start":
            ensure_relay(s)
            why = start(s)
            return self.answer(409, {"message": why}) if why else self.answer(200, s.view())
        if action == "stop":
            threading.Thread(target=stop, args=(s,), daemon=True).start()
            return self.answer(202, s.view())
        if action == "command":
            try:
                line = self.body().get("line", "")
            except (ValueError, AttributeError):
                line = None
            if not isinstance(line, str):
                return self.answer(400, {"message": "no line"})
            return self.answer(204) if command(s, line) else self.answer(409, {"message": "not running"})
        self.answer(404)

    def do_DELETE(self):
        if not self.allowed():
            return
        parts, key, _ = self.target()
        if parts is None or key is None:
            return
        s = server(key)
        if s is None:
            return self.answer(404)
        with s.guard:
            stop(s)
            close_relay(key)
            shutil.rmtree(s.dir, ignore_errors=True)
            with lock:
                servers.pop(key, None)
        self.answer(204)


class ApiServer(ThreadingHTTPServer):
    """The API's server: at most MAX_CONNECTIONS requests at once; one more is answered 503 and closed at once."""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.places = threading.BoundedSemaphore(MAX_CONNECTIONS)

    def process_request(self, request, client_address):
        if not self.places.acquire(blocking=False):
            try:
                request.sendall(b"HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            except OSError:
                pass
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.places.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.places.release()


def main() -> None:
    try:
        os.makedirs(os.path.join(DATA, "servers"), exist_ok=True)
    except PermissionError:
        # Docker made the mounted folder itself (as root), because it wasn't there
        print(f"{DATA} isn't writable for uid {os.getuid()}: on the host, data/minecraft/ must belong to it "
              f"(mkdir -p data/minecraft && chown {os.getuid()} data/minecraft)", flush=True)
        raise SystemExit(1)
    if not TOKEN:
        print("No MINECRAFT_TOKEN: every call of the gateway is refused, no Minecraft server runs (set it in .env, "
              "the same for the gateway)", flush=True)
    # The servers that have a folder (asleep): known again after a restart
    for key in sorted(os.listdir(os.path.join(DATA, "servers"))):
        if KEY.match(key):
            ensure_relay(server(key, create=True))
    threading.Thread(target=watchdog, daemon=True).start()

    # The service stops (a deploy): every server saves its world first
    def stop_all(*_):
        threads = [threading.Thread(target=stop, args=(s,)) for s in list(servers.values())]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        os._exit(0)

    signal.signal(signal.SIGTERM, stop_all)
    print(f"Minecraft host on port {API_PORT}: {len(servers)} server folder(s), {AT_ONCE} at once, -Xmx{HEAP}, "
          f"{LIMIT_MB} MB each", flush=True)
    ApiServer(("0.0.0.0", API_PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
