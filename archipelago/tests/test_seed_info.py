"""seed_info.py without Archipelago: its bounds, and a stand-in Utils for what it reads.

Run from the repository: python -m unittest discover -s archipelago/tests
"""
import io
import os
import pickle
import sys
import tempfile
import types
import unittest
import zipfile
import zlib
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

import seed_info  # noqa: E402


def stub_utils() -> types.ModuleType:
    """Utils as far as seed_info.py uses it; its unpickler "imports" a module whose name ends in Options, as
    Archipelago's does (here it only notes it)."""
    utils = types.ModuleType("Utils")
    utils.version_tuple = (0, 6, 8)
    utils.imported = []

    class RestrictedUnpickler(pickle.Unpickler):
        def find_class(self, module, name):
            if module.endswith("Options"):
                utils.imported.append(module)
                return dict
            raise pickle.UnpicklingError(f"global '{module}.{name}' is forbidden")

    utils.RestrictedUnpickler = RestrictedUnpickler
    utils.restricted_loads = lambda data: RestrictedUnpickler(io.BytesIO(data)).load()
    return utils


def multidata(decoded: dict) -> bytes:
    return b"\x03" + zlib.compress(pickle.dumps(decoded, protocol=2))


GOOD = {"seed_name": "S1", "version": (0, 6, 8), "minimum_versions": {"server": (0, 6, 0)}, "slot_info": {}}


class ReadTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.utils = stub_utils()
        patches = [
            mock.patch.dict(sys.modules, {"Utils": self.utils}),
            mock.patch.object(seed_info, "bound_memory", lambda extra: None),
        ]
        for patch in patches:
            patch.start()
            self.addCleanup(patch.stop)

    def tearDown(self):
        self.dir.cleanup()

    def zip(self, entries: dict) -> str:
        path = os.path.join(self.dir.name, "AP_1.zip")
        with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
            for name, data in entries.items():
                archive.writestr(name, data)
        return path

    def refused_without_archipelago(self, path: str) -> str:
        """The problem seed_info.py finds in [path] without importing Archipelago."""
        with mock.patch.dict(sys.modules, {"Utils": None, "MultiServer": None}):
            found = seed_info.read(path)
        self.assertIn("problem", found)
        return found["problem"]

    def test_a_good_seed(self):
        found = seed_info.read(self.zip({"AP_1.archipelago": multidata(GOOD)}))
        self.assertEqual({"seed_name": "S1", "version": "0.6.8", "slots": []}, found)

    def test_one_that_needs_a_newer_server(self):
        found = seed_info.read(self.zip({"AP_1.archipelago": multidata({**GOOD, "minimum_versions": {"server": (0, 7, 0)}})}))
        self.assertEqual({"problem": "it needs Archipelago 0.7.0 or newer to be served"}, found)

    def test_more_than_the_limit_unpacked(self):
        with mock.patch.object(seed_info, "LIMIT", 1024 * 1024):
            problem = self.refused_without_archipelago(
                self.zip({"AP_1.archipelago": b"\x03" + zlib.compress(b"\0" * (2 * 1024 * 1024))}))
        self.assertIn("unpacks to more than 1 MB", problem)

    def test_a_packed_file_larger_than_the_limit_isnt_read(self):
        with mock.patch.object(seed_info, "LIMIT", 1024 * 1024):
            problem = self.refused_without_archipelago(self.zip({"AP_1.archipelago": os.urandom(2 * 1024 * 1024)}))
        self.assertIn("unpacks to more than 1 MB", problem)

    def test_not_exactly_one_multidata(self):
        self.assertIn("no .archipelago file", self.refused_without_archipelago(self.zip({"x.txt": b""})))
        two = self.zip({"a.archipelago": multidata(GOOD), "b.archipelago": multidata(GOOD)})
        self.assertIn("more than one", self.refused_without_archipelago(two))

    def test_another_format(self):
        problem = self.refused_without_archipelago(self.zip({"AP_1.archipelago": b"\x04" + zlib.compress(b"x")}))
        self.assertIn("of a format", problem)

    def test_cut_off(self):
        data = multidata(GOOD)
        problem = self.refused_without_archipelago(self.zip({"AP_1.archipelago": data[:-8]}))
        self.assertIn("cut off", problem)

    def test_not_zlib(self):
        problem = self.refused_without_archipelago(self.zip({"AP_1.archipelago": b"\x03not zlib at all"}))
        self.assertIn("isn't a multidata file", problem)

    def test_no_module_a_file_names_is_imported(self):
        raw = b"cworlds.alttp.Options\nThing\n."
        found = seed_info.read(self.zip({"AP_1.archipelago": b"\x03" + zlib.compress(raw)}))
        self.assertIn("doesn't read", found["problem"])
        self.assertEqual([], self.utils.imported)

    def test_the_other_scripts_read_as_multiserver_does(self):
        # The first .archipelago of a zip, unbounded, with Archipelago's own loader
        path = self.zip({"a.archipelago": multidata(GOOD), "b.archipelago": multidata({**GOOD, "seed_name": "S2"})})
        decoded = seed_info.loads(seed_info.unpacked(seed_info.multidata_bytes(path)))
        self.assertEqual("S1", decoded["seed_name"])


if __name__ == "__main__":
    unittest.main()
