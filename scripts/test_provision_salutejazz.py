import importlib.util
import os
import re
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
SCRIPT = HERE.parent / "deploy/provision_salutejazz.py"
DEPLOY = HERE / "deploy-olcrtc-vds.sh"
ROOMS = [f"room{i}:pass{i}word" for i in range(1, 5)]


class ProvisionSaluteJazzTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.log = self.root / "calls.log"
        self.tool = self.root / "fake-room-tool"
        self.tool.write_text(
            "#!/usr/bin/env python3\n"
            "import os,sys\n"
            "from pathlib import Path\n"
            "log=Path(os.environ['FAKE_TOOL_LOG'])\n"
            "with log.open('a') as stream: stream.write('call\\n')\n"
            "if os.environ.get('FAKE_TOOL_FAIL'): sys.exit(1)\n"
            "rooms=os.environ.get('FAKE_TOOL_ROOMS','room1:pass1word,room2:pass2word,room3:pass3word,room4:pass4word').split(',')\n"
            "print(rooms[len(log.read_text().splitlines())-1])\n"
        )
        self.tool.chmod(0o755)
        self.output = self.root / "rooms.conf"
        self.env = os.environ.copy()
        self.env["FAKE_TOOL_LOG"] = str(self.log)

    def tearDown(self):
        self.temp.cleanup()

    def run_script(self, output=None, env=None):
        actual_env = self.env.copy()
        if env:
            actual_env.update(env)
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--tool", str(self.tool), "--output", str(output or self.output)],
            text=True,
            capture_output=True,
            env=actual_env,
            timeout=10,
        )

    def values(self, path=None):
        text = (path or self.output).read_text()
        return dict(re.findall(r"^(SALUTEJAZZ_(?:URL|KEY)_[1-4])='([^']*)'$", text, re.MULTILINE))

    def seed_partial(self):
        self.output.write_text(
            "SALUTEJAZZ_URL_1='room1:pass1word'\nSALUTEJAZZ_KEY_1='aa'\n"
            "SALUTEJAZZ_URL_2='room2:pass2word'\nSALUTEJAZZ_KEY_2='aa'\n".replace("aa", "a" * 64)
        )
        self.output.chmod(0o600)

    def test_creates_four_rooms_and_reuses_the_complete_file(self):
        first = self.run_script()
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        values = self.values()
        self.assertEqual(ROOMS, [values[f"SALUTEJAZZ_URL_{i}"] for i in range(1, 5)])
        keys = [values[f"SALUTEJAZZ_KEY_{i}"] for i in range(1, 5)]
        self.assertEqual([keys[0]] * 4, keys)
        self.assertRegex(keys[0], r"^[0-9a-f]{64}$")
        self.assertEqual(4, len(self.log.read_text().splitlines()))
        self.assertEqual(0o600, stat.S_IMODE(self.output.stat().st_mode))
        for secret in ROOMS + keys:
            self.assertNotIn(secret, first.stdout + first.stderr)
        before = self.output.read_bytes()

        second = self.run_script()

        self.assertEqual(0, second.returncode, second.stdout + second.stderr)
        self.assertEqual(before, self.output.read_bytes())
        self.assertEqual(4, len(self.log.read_text().splitlines()))

    def test_partial_file_starting_after_first_index_keeps_its_key(self):
        key = "b" * 64
        self.output.write_text(f"SALUTEJAZZ_URL_2='room2:pass2word'\nSALUTEJAZZ_KEY_2='{key}'\n")
        result = self.run_script(env={"FAKE_TOOL_ROOMS": "room1:pass1word,room3:pass3word,room4:pass4word"})
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        values = self.values()
        self.assertEqual([key] * 4, [values[f"SALUTEJAZZ_KEY_{i}"] for i in range(1, 5)])

    def test_malformed_existing_file_is_not_replaced(self):
        self.output.write_text("original\n")
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(b"original\n", self.output.read_bytes())
        self.assertFalse(self.log.exists())

    def test_duplicate_new_rooms_leave_no_file(self):
        result = self.run_script(env={"FAKE_TOOL_ROOMS": "same1:pass1word,same1:pass1word,same1:pass1word,same1:pass1word"})
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.output.exists())
        self.assertNotIn("same1:pass1word", result.stdout + result.stderr)

    def test_tool_failure_preserves_the_previous_file(self):
        self.seed_partial()
        before = self.output.read_bytes()
        result = self.run_script(env={"FAKE_TOOL_FAIL": "1"})
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(before, self.output.read_bytes())

    def test_interrupted_replace_preserves_the_previous_file(self):
        self.seed_partial()
        before = self.output.read_bytes()
        spec = importlib.util.spec_from_file_location("provision_salutejazz", SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with mock.patch.dict(
            os.environ,
            {"FAKE_TOOL_LOG": str(self.log), "FAKE_TOOL_ROOMS": "room3:pass3word,room4:pass4word"},
        ), mock.patch.object(module.os, "replace", side_effect=OSError("interrupted")):
            with self.assertRaises(OSError):
                module.provision(self.tool, self.output)
        self.assertEqual(before, self.output.read_bytes())

    def test_deploy_wrapper_runs_without_a_vds_config(self):
        result = subprocess.run(
            ["bash", str(DEPLOY), "provision-salutejazz", "--tool", str(self.tool), "--rooms-file", str(self.output)],
            text=True,
            capture_output=True,
            env=self.env,
            timeout=10,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(8, len(self.values()))


if __name__ == "__main__":
    unittest.main()
