"""minecraft_host.py (standard library only): its log lines, its console, its API's bounds and one server's lock.

Run from the repository: python -m unittest discover -s archipelago/tests
"""
import http.client
import io
import json
import os
import socket
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

import minecraft_host  # noqa: E402

TOKEN = "test-token"


class FakeProcess:
    """A Java server that runs until [ended] is set."""

    def __init__(self, ended: threading.Event):
        self.ended = ended
        self.stdin = io.StringIO()
        self.stdout = []
        self.pid = 0

    def poll(self):
        return 0 if self.ended.is_set() else None

    def wait(self, timeout=None):
        self.ended.wait(timeout)
        return 0

    def kill(self):
        self.ended.set()


class Folder(unittest.TestCase):
    """A test with DATA in a temporary folder and no servers known."""

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.ended = threading.Event()
        patches = [
            mock.patch.object(minecraft_host, "DATA", self.dir.name),
            mock.patch.object(minecraft_host, "TOKEN", TOKEN),
            mock.patch.object(minecraft_host, "AT_ONCE", 1),
            mock.patch.dict(minecraft_host.servers, clear=True),
        ]
        for patch in patches:
            patch.start()
            self.addCleanup(patch.stop)

    def tearDown(self):
        self.ended.set()
        self.dir.cleanup()

    def server(self, key="abc-p1", seed="AP_1.zip") -> minecraft_host.Server:
        s = minecraft_host.server(key, create=True)
        os.makedirs(s.dir, exist_ok=True)
        with open(os.path.join(s.dir, "randogeddon.json"), "w", encoding="utf-8") as f:
            json.dump({"session": "abc", "slot": "Steve-Dig", "seed": seed}, f)
        with open(os.path.join(s.dir, "eula.txt"), "w", encoding="utf-8") as f:
            f.write("eula=true\n")
        return s


class LogLinesTest(unittest.TestCase):
    """A11 / S11: only the server's own line counts as a join or a leave, never a chat line."""

    def test_a_join_and_a_leave(self):
        joined = minecraft_host.JOINED.match(
            "[12:00:00] [Server thread/INFO] [minecraft/MinecraftServer]: Steve_1 joined the game")
        self.assertEqual("Steve_1", joined.group(1))
        left = minecraft_host.LEFT.match("[12:00:00] [Server thread/INFO]: Steve_1 left the game")
        self.assertEqual("Steve_1", left.group(1))

    def test_a_chat_line_cant_forge_one(self):
        for line in (
            "[12:00:00] [Server thread/INFO] [minecraft/MinecraftServer]: <Bob> x]: Ghost joined the game",
            "[12:00:00] [Async Chat Thread - #0/INFO] [minecraft/MinecraftServer]: <Bob> Ghost joined the game",
            "[12:00:00] [Server thread/INFO] [minecraft/MinecraftServer]: [Bob: Ghost joined the game]",
        ):
            self.assertIsNone(minecraft_host.JOINED.match(line), line)
        self.assertIsNone(minecraft_host.LEFT.match(
            "[12:00:00] [Server thread/INFO] [minecraft/MinecraftServer]: <Bob> a]: Alice left the game"))


class ConsoleTest(Folder):
    """A24: one console line, whatever it holds; A26: the slot's password never in its log."""

    def test_one_line(self):
        s = self.server()
        s.process = FakeProcess(self.ended)
        self.assertTrue(minecraft_host.command(s, "say a\rstop\nb"))
        self.assertEqual("say a stop b\n", s.process.stdin.getvalue())

    def test_the_password_is_masked(self):
        s = self.server()
        s.process = FakeProcess(self.ended)
        minecraft_host.command(s, 'connect 127.0.0.1 39000 "s3cret-pw"')
        s.note('[Server thread/INFO]: Connecting with "s3cret-pw"')
        texts = [line["text"] for line in s.lines]
        self.assertTrue(any("***" in text for text in texts))
        self.assertFalse(any("s3cret-pw" in text for text in texts))


class StartTest(Folder):
    """A14: one server isn't started twice, however many ask at once."""

    def test_twenty_at_once_start_one(self):
        s = self.server()
        started = []

        def popen(*args, **kwargs):
            started.append(args)
            time.sleep(0.05)
            return FakeProcess(self.ended)

        with mock.patch.object(minecraft_host.subprocess, "Popen", popen):
            threads = [threading.Thread(target=minecraft_host.start, args=(s,)) for _ in range(20)]
            for thread in threads:
                thread.start()
            for thread in threads:
                thread.join()
        self.assertEqual(1, len(started))

    def test_the_most_at_once(self):
        first, second = self.server("abc-p1"), self.server("abc-p2")
        with mock.patch.object(minecraft_host.subprocess, "Popen", lambda *a, **k: FakeProcess(self.ended)):
            self.assertEqual("", minecraft_host.start(first))
            self.assertIn("the most at once", minecraft_host.start(second))


class ApiTest(Folder):
    """A22 / S12: the API's timeouts, its bound on connections, its answers to what it doesn't take; A14: PUT."""

    def setUp(self):
        super().setUp()
        patches = [
            mock.patch.object(minecraft_host, "MAX_CONNECTIONS", 2),
            mock.patch.object(minecraft_host.Handler, "timeout", 1),
        ]
        for patch in patches:
            patch.start()
            self.addCleanup(patch.stop)
        self.api = minecraft_host.ApiServer(("127.0.0.1", 0), minecraft_host.Handler)
        self.port = self.api.server_address[1]
        threading.Thread(target=self.api.serve_forever, daemon=True).start()
        self.addCleanup(self.api.server_close)
        self.addCleanup(self.api.shutdown)

    def request(self, method, path, body=None, token=TOKEN):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        headers = {"Authorization": f"Bearer {token}"} if token else {}
        if body is not None:
            headers["Content-Type"] = "application/json"
            body = json.dumps(body)
        connection.request(method, path, body=body, headers=headers)
        response = connection.getresponse()
        data = response.read()
        connection.close()
        return response.status, data

    def test_no_token_no_answer(self):
        self.assertEqual(401, self.request("GET", "/servers", token=None)[0])

    def test_since_that_isnt_a_number(self):
        self.server()
        self.assertEqual(400, self.request("GET", "/servers/abc-p1/log?since=x")[0])
        self.assertEqual(200, self.request("GET", "/servers/abc-p1/log?since=0")[0])

    def test_a_silent_connection_is_closed(self):
        silent = socket.create_connection(("127.0.0.1", self.port))
        silent.settimeout(10)
        try:
            started = time.monotonic()
            self.assertEqual(b"", silent.recv(100))
            self.assertLess(time.monotonic() - started, 9)
        finally:
            silent.close()

    def test_more_connections_than_allowed_are_refused(self):
        silent = [socket.create_connection(("127.0.0.1", self.port)) for _ in range(2)]
        try:
            time.sleep(0.3)  # both taken
            third = socket.create_connection(("127.0.0.1", self.port))
            third.settimeout(5)
            try:
                self.assertTrue(third.recv(100).startswith(b"HTTP/1.1 503"))
            finally:
                third.close()
        finally:
            for connection in silent:
                connection.close()

    def test_a_running_servers_files_stay(self):
        s = self.server(seed="AP_1.zip")
        s.process = FakeProcess(self.ended)
        body = {"session": "abc", "slot": "Steve-Dig", "seed": "AP_1.zip", "data": "{}", "eula": True}
        self.assertEqual(200, self.request("PUT", "/servers/abc-p1", body)[0])
        self.assertFalse(os.path.exists(os.path.join(s.dir, "APData")))
        status, data = self.request("PUT", "/servers/abc-p1", {**body, "seed": "AP_2.zip"})
        self.assertEqual(409, status)
        self.assertIn(b"it runs", data)


if __name__ == "__main__":
    unittest.main()
