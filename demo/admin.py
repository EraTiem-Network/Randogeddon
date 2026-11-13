"""The demo's admin, through the admin API like the admin panel (scripts/demo.sh runs it in the gateway image).

    admin.py create <id> <group>   creates session <id> from demo/<group>/ (its YAMLs and session.json); its players
                                   need a login already (scripts/demo.sh makes them). About one in three random demo
                                   seeds can't be generated ("Game appears as unbeatable"): up to 5 attempts, then
                                   exit 3
    admin.py prune <id>...         deletes the demo sessions (demo-...) other than these, finished or not (one left
                                   running by an earlier demo has no players any more)
    admin.py check                 only logs in (exit 1 if that fails)

The admin's name and password come from DEMO_ADMIN and DEMO_PASSWORD, the tracker from DEMO_TRACKER
(http://tracker:8000 inside the compose network).
"""
import http.cookiejar
import json
import os
import pathlib
import sys
import urllib.error
import urllib.request

TRACKER = os.environ.get("DEMO_TRACKER", "http://tracker:8000").rstrip("/")
cookies = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))


def call(method: str, path: str, body=None, timeout=60):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(TRACKER + path, data=data, method=method,
                                     headers={"Content-Type": "application/json"} if data else {})
    try:
        with opener.open(request, timeout=timeout) as response:
            text = response.read().decode()
            return response.status, (json.loads(text) if text else None)
    except urllib.error.HTTPError as e:
        text = e.read().decode()
        try:
            return e.code, json.loads(text)
        except ValueError:
            return e.code, {"message": text}


def login():
    status, answer = call("POST", "/api/login", {"username": os.environ["DEMO_ADMIN"], "password": os.environ["DEMO_PASSWORD"]})
    if status != 204:
        sys.exit(f"Login as {os.environ['DEMO_ADMIN']} failed ({status}): {answer}")


def create(id_: str, group: str):
    directory = pathlib.Path("/demo") / group
    setup = json.loads((directory / "session.json").read_text())
    yamls = [{"fileName": f.name, "content": f.read_text()} for f in sorted(directory.glob("*.yaml"))]
    # session.json names the players; the API takes their account IDs
    status, users = call("GET", "/api/admin/users")
    if status != 200:
        sys.exit(f"{id_}: the accounts: {status} {users}")
    ids = {u["name"].lower(): u["id"] for u in users if u.get("name")}
    missing = sorted({n for w in setup["worlds"] for n in w.get("users", []) if n.lower() not in ids})
    if missing:
        sys.exit(f"{id_}: no login for {', '.join(missing)} (randogeddon logins --create <name>)")
    worlds = [{**{k: v for k, v in w.items() if k != "users"}, "userIds": [ids[n.lower()] for n in w.get("users", [])]}
              for w in setup["worlds"]]
    request = {"id": id_, "yamls": yamls, "worlds": worlds, "options": setup.get("options", {})}
    for attempt in range(1, 6):
        status, answer = call("POST", "/api/admin/sessions", request, timeout=12 * 60)
        if status != 422:
            break
        print(f"{id_}: no seed (attempt {attempt}): {answer.get('message', '').splitlines()[0]}", file=sys.stderr)
    else:
        sys.exit(3)
    if status != 200:
        sys.exit(f"{id_}: {status} {answer}")
    print(f"Session {id_} (seed {answer['seedName']}); locked: {', '.join(answer['locked']) or 'none'}")


def prune(keep: list[str]):
    status, overview = call("GET", "/api/admin/sessions")
    if status != 200:
        sys.exit(f"{status} {overview}")
    for session in overview["sessions"]:
        if session["id"].startswith("demo-") and session["id"] not in keep:
            status, _ = call("DELETE", f"/api/admin/sessions/{session['id']}")
            print(f"Deleted {session['id']}" if status == 204 else f"Could not delete {session['id']} ({status})")


login()
if sys.argv[1:2] == ["create"] and len(sys.argv) == 4:
    create(sys.argv[2], sys.argv[3])
elif sys.argv[1:2] == ["prune"]:
    prune(sys.argv[2:])
elif sys.argv[1:] == ["check"]:
    print(f"Logged in as {os.environ['DEMO_ADMIN']}")
else:
    sys.exit(__doc__)
