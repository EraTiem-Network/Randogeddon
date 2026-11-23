"""serve.py without Archipelago: a stand-in MultiServer whose Context loads and writes its save as Archipelago 0.6.8's
does (MultiServer.py: Context.init_save, _save, set_save).

Run from the repository: python -m unittest discover -s archipelago/tests
"""
import io
import logging
import os
import pickle
import sys
import tempfile
import types
import unittest
import zlib
from contextlib import redirect_stdout

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))


def stub_multiserver() -> types.ModuleType:
    multiserver = types.ModuleType("MultiServer")
    utils = types.ModuleType("Utils")
    utils.handlers = []

    def init_logging(name, **_):
        root = logging.getLogger()
        for handler in root.handlers[:]:
            root.removeHandler(handler)
        handler = logging.StreamHandler(io.StringIO())
        utils.handlers.append(handler)
        root.addHandler(handler)
        root.setLevel(logging.INFO)

    utils.init_logging = init_logging
    multiserver.Utils = utils
    multiserver.restricted_loads = pickle.loads

    class Context:
        def __init__(self, save_filename, connect_names="seed"):
            self.save_filename = save_filename
            self.connect_names = connect_names
            self.logger = logging.getLogger("stub-server")
            self.started = 0
            self.data = None
            self.password = None
            self.server_password = "admin"
            self.item_cheat = True
            self.fail_save = False

        # As Archipelago's: a save it can't read is logged, and it goes on with an empty game
        def init_save(self, enabled=True):
            self.saving = enabled
            if self.saving:
                try:
                    with open(self.save_filename, "rb") as f:
                        self.set_save(multiserver.restricted_loads(zlib.decompress(f.read())))
                except FileNotFoundError:
                    pass
                except Exception as e:
                    self.logger.debug(e)
                self._start_async_saving()

        def _start_async_saving(self, atexit_save=True):
            self.started += 1

        def set_save(self, savedata):
            if self.connect_names != savedata["connect_names"]:
                raise Exception("This savegame does not appear to match the loaded multiworld.")
            self.data = savedata
            self.item_cheat = savedata["game_options"].get("item_cheat", True)

        def get_save(self):
            if self.fail_save:
                raise RuntimeError("can't make the save")
            return {"connect_names": self.connect_names,
                    "game_options": {"password": self.password, "server_password": self.server_password}}

        def _save(self, exit_save=False):
            try:
                encoded = pickle.dumps(self.get_save())
                with open(self.save_filename, "wb") as f:
                    f.write(zlib.compress(encoded))
            except Exception:
                return False
            return True

    class ClientMessageProcessor:
        commands = {}

    async def process_client_cmd(ctx, client, args):
        pass

    multiserver.Context = Context
    multiserver.ClientMessageProcessor = ClientMessageProcessor
    multiserver.process_client_cmd = process_client_cmd
    return multiserver


MULTISERVER = stub_multiserver()
sys.modules["MultiServer"] = MULTISERVER
import serve  # noqa: E402

serve.install()
serve.ROOM_PASSWORD = "room-secret"


def save_bytes(connect_names="seed") -> bytes:
    return zlib.compress(pickle.dumps({"connect_names": connect_names, "game_options": {"item_cheat": True}}))


class SaveTest(unittest.TestCase):
    """A7: a save that can't be read is kept and not started over; a readable one is backed up; saves are whole."""

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.dir.name, "AP_1.apsave")

    def tearDown(self):
        self.dir.cleanup()

    def files(self) -> list:
        return sorted(os.listdir(self.dir.name))

    def test_a_readable_save_is_loaded_and_backed_up(self):
        with open(self.path, "wb") as f:
            f.write(save_bytes())
        ctx = MULTISERVER.Context(self.path)
        ctx.init_save(True)
        self.assertIsNotNone(ctx.data)
        self.assertEqual(1, ctx.started)
        self.assertEqual(["AP_1.apsave", "AP_1.apsave.bak"], self.files())
        with open(self.path + ".bak", "rb") as f:
            self.assertEqual(save_bytes(), f.read())
        self.assertEqual("room-secret", ctx.password)
        self.assertIsNone(ctx.server_password)
        self.assertFalse(ctx.item_cheat)

    def test_no_save_starts_a_new_game(self):
        ctx = MULTISERVER.Context(self.path)
        ctx.init_save(True)
        self.assertEqual(1, ctx.started)
        self.assertEqual([], self.files())

    def refused(self, ctx) -> str:
        output = io.StringIO()
        with redirect_stdout(output), self.assertRaises(SystemExit) as ended:
            ctx.init_save(True)
        self.assertEqual(serve.SAVE_PROBLEM_EXIT, ended.exception.code)
        self.assertEqual(0, ctx.started)
        return output.getvalue().strip().splitlines()[-1]

    def test_an_unreadable_save_is_kept_and_not_started_over(self):
        with open(self.path, "wb") as f:
            f.write(b"cut off")
        line = self.refused(MULTISERVER.Context(self.path))
        self.assertTrue(line.startswith("[randogeddon-save] The save AP_1.apsave can't be read ("), line)
        self.assertIn("kept as it is and as AP_1.apsave.broken-", line)
        self.assertIn("not starting a new game over it", line)
        with open(self.path, "rb") as f:
            self.assertEqual(b"cut off", f.read())
        broken = [name for name in self.files() if ".broken-" in name]
        self.assertEqual(1, len(broken))
        # The next start: the same bytes, no second copy
        self.refused(MULTISERVER.Context(self.path))
        self.assertEqual(broken, [name for name in self.files() if ".broken-" in name])

    def test_another_seeds_save_is_refused_too(self):
        with open(self.path, "wb") as f:
            f.write(save_bytes("another"))
        line = self.refused(MULTISERVER.Context(self.path))
        self.assertIn("does not appear to match", line)

    def test_the_last_starts_backup_is_named(self):
        with open(self.path + ".bak", "wb") as f:
            f.write(save_bytes())
        with open(self.path, "wb") as f:
            f.write(b"")
        line = self.refused(MULTISERVER.Context(self.path))
        self.assertTrue(line.endswith("; the save of the last start is AP_1.apsave.bak"), line)

    def test_at_most_a_few_broken_copies(self):
        with open(self.path, "wb") as f:
            for n in range(serve.BROKEN_KEPT + 2):
                f.seek(0)
                f.write(bytes([n]))
                f.flush()
                serve.keep_broken(self.path, now=1_000_000 + n)
        broken = [name for name in self.files() if ".broken-" in name]
        self.assertEqual(serve.BROKEN_KEPT, len(broken))
        with open(os.path.join(self.dir.name, broken[-1]), "rb") as f:
            self.assertEqual(bytes([serve.BROKEN_KEPT + 1]), f.read())

    def test_a_save_is_written_whole_or_not_at_all(self):
        with open(self.path, "wb") as f:
            f.write(save_bytes())
        ctx = MULTISERVER.Context(self.path)
        ctx.init_save(True)
        self.assertTrue(ctx._save())
        self.assertEqual(self.path, ctx.save_filename)
        self.assertNotIn("AP_1.apsave.tmp", self.files())
        with open(self.path, "rb") as f:
            saved = pickle.loads(zlib.decompress(f.read()))
        self.assertIsNone(saved["game_options"]["password"])
        before = open(self.path, "rb").read()
        ctx.fail_save = True
        self.assertFalse(ctx._save())
        self.assertEqual(before, open(self.path, "rb").read())
        self.assertNotIn("AP_1.apsave.tmp", self.files())


class PasswordTest(unittest.TestCase):
    """A4: the room password never reaches a log; A20: the first line isn't waited for for ever."""

    def test_the_filter_masks_messages_and_tracebacks(self):
        stream = io.StringIO()
        handler = logging.StreamHandler(stream)
        handler.addFilter(serve.WithoutSecret("room-secret"))
        logger = logging.getLogger("serve-test")
        logger.addHandler(handler)
        logger.propagate = False
        try:
            logger.warning("Hosting game at %s:%d (%s)", "0.0.0.0", 38281, "Password: room-secret")
            try:
                raise ValueError("refused {'password': 'room-secret'}")
            except ValueError:
                logger.exception("A client connection was refused")
        finally:
            logger.removeHandler(handler)
        self.assertNotIn("room-secret", stream.getvalue())
        self.assertIn("Password: ***", stream.getvalue())
        self.assertIn("'password': '***'", stream.getvalue())

    def test_multiservers_logging_is_masked(self):
        root = logging.getLogger()
        before = root.handlers[:], root.filters[:], root.level
        try:
            serve.mask_logs("room-secret")
            MULTISERVER.Utils.init_logging(name="Server")
            logging.info("Hosting game at %s (%s)", "0.0.0.0:38281", "Password: room-secret")
            written = MULTISERVER.Utils.handlers[-1].stream.getvalue()
        finally:
            root.handlers[:], root.filters[:] = before[0], before[1]
            root.setLevel(before[2])
        self.assertIn("Password: ***", written)
        self.assertNotIn("room-secret", written)

    def test_the_first_line_is_the_password(self):
        self.assertEqual("pw", serve.room_password(io.StringIO("pw\n/players\n")))
        with self.assertRaises(SystemExit):
            serve.room_password(io.StringIO("\n"))

    @unittest.skipIf(os.name == "nt", "select() waits on pipes only on POSIX")
    def test_a_silent_gateway_isnt_waited_for_for_ever(self):
        read, write = os.pipe()
        try:
            with os.fdopen(read) as stream, self.assertRaises(SystemExit) as ended:
                serve.room_password(stream, seconds=0.2)
            self.assertIn("within 0.2 s", str(ended.exception.code))
        finally:
            os.close(write)


if __name__ == "__main__":
    unittest.main()
