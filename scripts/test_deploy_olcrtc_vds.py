import os
import subprocess
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "deploy-olcrtc-vds.sh"

FAKE_SSH = r'''#!/usr/bin/env python3
import os
import sys
with open(os.environ["FAKE_REMOTE_LOG"], "a") as log:
    log.write(" ".join(sys.argv[1:]) + "\n")
if os.environ.get("FAKE_SSH_EXIT"):
    sys.stderr.write("remote rejected " + os.environ.get("FAKE_SECRET", "") + "\n")
    sys.exit(int(os.environ["FAKE_SSH_EXIT"]))
print("PRETTY_NAME=Debian GNU/Linux 13")
print("MemTotal: 2048000 kB")
print("/dev/vda1 20G 8G 12G 45% /")
print("127.0.0.1:10000")
print("ilyukhaline-olcrtc@m1.service")
print("xray.service")
print("hysteria-server.service")
'''


class DeployPreflightTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        ssh = self.bin / "ssh"
        ssh.write_text(FAKE_SSH)
        ssh.chmod(0o755)
        scp = self.bin / "scp"
        scp.write_text("#!/usr/bin/env sh\nexit 0\n")
        scp.chmod(0o755)
        self.remote_log = self.root / "remote.log"
        self.config = self.root / "vds.conf"
        self.write_config({})
        self.env = os.environ.copy()
        self.env.update({
            "PATH": f"{self.bin}{os.pathsep}{self.env['PATH']}",
            "FAKE_REMOTE_LOG": str(self.remote_log),
        })

    def tearDown(self):
        self.temp.cleanup()

    def values(self):
        result = {
            "SSH_TARGET": "root@192.0.2.10",
            "ARTIFACT_DIR": str(self.root / "artifacts"),
            "PUBLISH_PATH": "/var/www/ilyukhaline/private.txt",
            "PUBLISH_URL": "https://example.invalid/private.txt",
            "VLESS_UUID": "11111111-1111-1111-1111-111111111111",
            "VLESS_LISTEN": "127.0.0.1:10000",
        }
        for index in range(1, 5):
            result[f"MAMBA_URL_{index}"] = f"https://mamba.example.invalid/room-{index}"
            result[f"VKCALLS_URL_{index}"] = f"https://vk.com/call/join/fake-room-{index}"
            result[f"TELEMOST_URL_{index}"] = f"https://telemost.yandex.ru/j/permanent-room-{index}"
            for provider in ("MAMBA", "VKCALLS", "TELEMOST"):
                result[f"{provider}_KEY_{index}"] = f"{index:02d}" * 32
        return result

    def write_config(self, changes, remove=()):
        values = self.values()
        values.update(changes)
        for key in remove:
            values.pop(key, None)
        self.config.write_text("".join(f"{key}='{value}'\n" for key, value in values.items()))

    def run_script(self, *args, env=None):
        actual_env = self.env.copy()
        if env:
            actual_env.update(env)
        return subprocess.run(
            ["bash", str(SCRIPT), *args],
            text=True,
            capture_output=True,
            env=actual_env,
            timeout=10,
        )

    def assert_invalid(self, changes=None, remove=(), expected=""):
        self.write_config(changes or {}, remove)
        result = self.run_script("--config", str(self.config), "check")
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn(expected, result.stderr)

    def test_explicit_config_and_supported_command_are_required(self):
        self.assertNotEqual(0, self.run_script("check").returncode)
        self.assertNotEqual(0, self.run_script("--config", str(self.config), "destroy").returncode)

    def test_missing_config_field_is_named_without_secrets(self):
        secret = self.values()["MAMBA_KEY_3"]
        self.assert_invalid(remove=("MAMBA_KEY_3",), expected="MAMBA_KEY_3")
        result = self.run_script("--config", str(self.config), "check")
        self.assertNotIn(secret, result.stderr)

    def test_each_provider_requires_four_room_urls(self):
        self.assert_invalid(remove=("VKCALLS_URL_4",), expected="VKCALLS_URL_4")

    def test_telemost_rejects_temporary_or_noncanonical_urls(self):
        self.assert_invalid(
            {"TELEMOST_URL_2": "https://telemost.yandex.ru/j/temporary?expires=86400"},
            expected="TELEMOST_URL_2",
        )

    def test_duplicate_provider_and_room_are_rejected(self):
        self.assert_invalid(
            {"MAMBA_URL_2": self.values()["MAMBA_URL_1"]},
            expected="duplicate",
        )

    def test_vless_must_listen_on_loopback(self):
        self.assert_invalid({"VLESS_LISTEN": "0.0.0.0:10000"}, expected="loopback")
        self.assert_invalid({"VLESS_LISTEN": "192.0.2.10:10000"}, expected="loopback")

    def test_check_only_issues_read_only_remote_commands(self):
        result = self.run_script("--config", str(self.config), "check")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        commands = self.remote_log.read_text()
        self.assertIn("PRETTY_NAME", commands)
        self.assertIn("MemTotal", commands)
        self.assertIn("ss -ltn", commands)
        self.assertIn("systemctl list-units", commands)
        for forbidden in ("mkdir", "systemctl start", "systemctl restart", "systemctl stop", "scp ", "tee ", "rm "):
            self.assertNotIn(forbidden, commands)
        self.assertIn("Debian", result.stdout)
        self.assertIn("hysteria-server", result.stdout)

    def test_ssh_failure_is_reported_with_secrets_redacted(self):
        secret = self.values()["MAMBA_KEY_1"]
        result = self.run_script(
            "--config", str(self.config), "check",
            env={"FAKE_SSH_EXIT": "42", "FAKE_SECRET": secret},
        )
        self.assertEqual(42, result.returncode)
        self.assertNotIn(secret, result.stdout + result.stderr)
        self.assertIn("[REDACTED]", result.stderr)


if __name__ == "__main__":
    unittest.main()
