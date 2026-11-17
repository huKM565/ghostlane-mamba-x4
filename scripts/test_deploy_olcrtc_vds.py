import os
import subprocess
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "deploy-olcrtc-vds.sh"

FAKE_SSH = r'''#!/usr/bin/env python3
import os
import subprocess
import sys
from pathlib import Path
with open(os.environ["FAKE_REMOTE_LOG"], "a") as log:
    log.write(" ".join(sys.argv[1:]) + "\n")
if os.environ.get("FAKE_SSH_EXIT"):
    sys.stderr.write("remote rejected " + os.environ.get("FAKE_SECRET", "") + "\n")
    sys.exit(int(os.environ["FAKE_SSH_EXIT"]))
if os.environ.get("FAKE_REMOTE_ROOT") and "== OS ==" not in sys.argv[-1]:
    root = Path(os.environ["FAKE_REMOTE_ROOT"])
    stdin_mode = "-s" in sys.argv
    target_index = next(index for index, argument in enumerate(sys.argv) if argument.startswith("root@"))
    command = sys.stdin.read() if stdin_mode else " ".join(sys.argv[target_index + 1:])
    for prefix in ("/etc/ilyukhaline", "/etc/olcrtc", "/etc/systemd/system", "/opt/ilyukhaline", "/var/lib/ilyukhaline", "/tmp/ilyukhaline"):
        command = command.replace(prefix, str(root) + prefix)
    env = os.environ.copy()
    env["PATH"] = str(root / "remote-bin") + os.pathsep + env["PATH"]
    if stdin_mode:
        arguments = sys.argv[sys.argv.index("-s") + 1:]
        arguments[0] = str(root) + arguments[0]
        result = subprocess.run(["bash", "-s", *arguments], input=command, text=True, env=env)
    else:
        result = subprocess.run(["bash", "-c", command], env=env)
    sys.exit(result.returncode)
print("PRETTY_NAME=Debian GNU/Linux 13")
print("MemTotal: 2048000 kB")
print("/dev/vda1 20G 8G 12G 45% /")
print("127.0.0.1:10000")
print("ilyukhaline-olcrtc@m1.service")
print("xray.service")
print("hysteria-server.service")
'''

FAKE_SCP = r'''#!/usr/bin/env python3
import os
import shutil
import sys
from pathlib import Path
source = Path(sys.argv[-2])
target = sys.argv[-1].split(":", 1)[1]
for prefix in ("/etc/ilyukhaline", "/etc/olcrtc", "/etc/systemd/system", "/opt/ilyukhaline", "/var/lib/ilyukhaline", "/tmp/ilyukhaline"):
    if target.startswith(prefix):
        target = os.environ["FAKE_REMOTE_ROOT"] + target
        break
path = Path(target)
path.parent.mkdir(parents=True, exist_ok=True)
shutil.copyfile(source, path)
shutil.copystat(source, path)
'''

FAKE_SYSTEMCTL = r'''#!/usr/bin/env python3
import os
import sys
from pathlib import Path
root = Path(os.environ["FAKE_REMOTE_ROOT"])
log = root / "systemctl.log"
with log.open("a") as stream:
    stream.write(" ".join(sys.argv[1:]) + "\n")
action = sys.argv[1] if len(sys.argv) > 1 else ""
units = [value for value in sys.argv[2:] if value.endswith(".service")]
active = root / "active-units"
current = set(active.read_text().splitlines()) if active.exists() else set()
if action in ("start", "restart", "enable"):
    current.update(units)
elif action in ("stop", "disable"):
    current.difference_update(units)
elif action == "is-active":
    state = "active" if units and units[0] in current else "inactive"
    print(state)
    sys.exit(0 if state == "active" else 3)
active.write_text("\n".join(sorted(current)) + ("\n" if current else ""))
'''

FAKE_MAGE = r'''#!/usr/bin/env python3
import os
import sys
from pathlib import Path
with open(os.environ["FAKE_BUILD_LOG"], "a") as log:
    log.write(" ".join(sys.argv[1:]) + " GOOS=" + os.environ.get("GOOS", "") + " GOARCH=" + os.environ.get("GOARCH", "") + " CGO=" + os.environ.get("CGO_ENABLED", "") + "\n")
if sys.argv[1:] == ["build"]:
    output = Path("dist/olcrtc")
    output.parent.mkdir(exist_ok=True)
    output.write_text("#!/usr/bin/env sh\necho fake-olcrtc\n")
    output.chmod(0o755)
'''

FAKE_XRAY = r'''#!/usr/bin/env python3
import json
import os
import sys
if "-test" in sys.argv:
    config = sys.argv[sys.argv.index("-config") + 1]
    with open(config) as stream:
        json.load(stream)
    if os.environ.get("FAKE_XRAY_TEST_FAIL"):
        print("intentional invalid xray config", file=sys.stderr)
        sys.exit(1)
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
        scp.write_text(FAKE_SCP)
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

    def enable_remote(self):
        self.remote = self.root / "remote"
        remote_bin = self.remote / "remote-bin"
        remote_bin.mkdir(parents=True)
        systemctl = remote_bin / "systemctl"
        systemctl.write_text(FAKE_SYSTEMCTL)
        systemctl.chmod(0o755)
        mage = self.bin / "mage"
        mage.write_text(FAKE_MAGE)
        mage.chmod(0o755)
        self.source = self.root / "source"
        self.source.mkdir()
        (self.source / "magefile.go").write_text("package main\n")
        self.xray = self.root / "xray"
        self.xray.write_text(FAKE_XRAY)
        self.xray.chmod(0o755)
        self.build_log = self.root / "build.log"
        self.write_config({
            "OLCRTC_SOURCE": str(self.source),
            "XRAY_BINARY": str(self.xray),
        })
        self.env.update({
            "FAKE_REMOTE_ROOT": str(self.remote),
            "FAKE_BUILD_LOG": str(self.build_log),
        })

    def values(self):
        result = {
            "SSH_TARGET": "root@192.0.2.10",
            "ARTIFACT_DIR": str(self.root / "artifacts"),
            "PUBLISH_PATH": "/var/www/ilyukhaline/private.txt",
            "PUBLISH_URL": "https://example.invalid/private.txt",
            "VLESS_UUID": "11111111-1111-1111-1111-111111111111",
            "VLESS_LISTEN": "127.0.0.1:10000",
            "OLCRTC_SOURCE": str(self.root / "source"),
            "XRAY_BINARY": str(self.root / "xray"),
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


class DeployLifecycleTest(DeployPreflightTest):
    def setUp(self):
        super().setUp()
        self.enable_remote()

    def build(self):
        result = self.run_script("--config", str(self.config), "build")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def deploy(self, env=None):
        return self.run_script("--config", str(self.config), "deploy", env=env)

    def managed_snapshot(self):
        result = {}
        for relative in ("etc/ilyukhaline", "opt/ilyukhaline", "etc/systemd/system"):
            base = self.remote / relative
            if base.exists():
                for path in base.rglob("*"):
                    if path.is_file():
                        result[str(path.relative_to(self.remote))] = path.read_bytes()
        return result

    def test_build_produces_static_linux_amd64_artifacts_and_hashes(self):
        self.build()
        artifacts = self.root / "artifacts"
        self.assertTrue((artifacts / "olcrtc-linux-amd64").is_file())
        self.assertTrue((artifacts / "xray").is_file())
        self.assertEqual(64, len((artifacts / "olcrtc-linux-amd64.sha256").read_text().split()[0]))
        build = self.build_log.read_text()
        self.assertIn("GOOS=linux", build)
        self.assertIn("GOARCH=amd64", build)
        self.assertIn("CGO=0", build)

    def test_deploy_is_complete_and_idempotent_without_touching_unmanaged_vpn(self):
        unmanaged = self.remote / "etc/systemd/system/hysteria-server.service"
        unmanaged.parent.mkdir(parents=True)
        unmanaged.write_text("[Service]\nExecStart=/usr/bin/hysteria\n")
        self.build()
        first = self.deploy()
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        etc = self.remote / "etc/ilyukhaline"
        expected = [f"{provider}{index}.yaml" for provider in ("m", "v", "t") for index in range(1, 5)]
        self.assertEqual(sorted(expected + ["xray.json"]), sorted(path.name for path in etc.iterdir()))
        for provider in ("mamba", "vkcalls", "telemost"):
            rooms = [self.values()[f"{provider.upper()}_URL_{index}"] for index in range(1, 5)]
            self.assertEqual(4, len(set(rooms)))
            for room in rooms:
                self.assertTrue(any(room.encode() in path.read_bytes() for path in etc.glob("*.yaml")))
        self.assertIn('"port": 10000', (etc / "xray.json").read_text())
        snapshot = self.managed_snapshot()
        active_before = (self.remote / "active-units").read_bytes()
        second = self.deploy()
        self.assertEqual(0, second.returncode, second.stdout + second.stderr)
        self.assertEqual(snapshot, self.managed_snapshot())
        self.assertEqual(active_before, (self.remote / "active-units").read_bytes())
        self.assertEqual("[Service]\nExecStart=/usr/bin/hysteria\n", unmanaged.read_text())
        systemctl = (self.remote / "systemctl.log").read_text()
        self.assertNotIn("restart hysteria", systemctl)
        self.assertNotIn("stop hysteria", systemctl)

    def test_existing_german_m1_and_v1_are_adopted_not_duplicated(self):
        old = self.remote / "etc/olcrtc"
        old.mkdir(parents=True)
        m1 = "mode: srv\nroom:\n  id: adopted-m1\ncrypto:\n  key: adopted-m1-key\n"
        v1 = "mode: srv\nroom:\n  id: adopted-v1\ncrypto:\n  key: adopted-v1-key\n"
        (old / "m1.yaml").write_text(m1)
        (old / "v1.yaml").write_text(v1)
        self.build()
        result = self.deploy()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        managed = self.remote / "etc/ilyukhaline"
        self.assertEqual(m1, (managed / "m1.yaml").read_text())
        self.assertEqual(v1, (managed / "v1.yaml").read_text())
        self.assertFalse((managed / "m1-extra.yaml").exists())
        self.assertFalse((managed / "v1-extra.yaml").exists())
        systemctl = (self.remote / "systemctl.log").read_text()
        self.assertIn("disable olcrtc@m1.service olcrtc@v1.service", systemctl)
        self.assertIn("stop olcrtc@m1.service olcrtc@v1.service", systemctl)

    def test_invalid_xray_restores_previous_files_and_units(self):
        self.build()
        first = self.deploy()
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        snapshot = self.managed_snapshot()
        active = (self.remote / "active-units").read_bytes()
        failed = self.deploy(env={"FAKE_XRAY_TEST_FAIL": "1"})
        self.assertNotEqual(0, failed.returncode)
        self.assertEqual(snapshot, self.managed_snapshot())
        self.assertEqual(active, (self.remote / "active-units").read_bytes())
        systemctl = (self.remote / "systemctl.log").read_text()
        self.assertNotIn("hysteria", systemctl)

    def test_status_reports_labels_and_health_without_secrets(self):
        self.build()
        self.assertEqual(0, self.deploy().returncode)
        result = self.run_script("--config", str(self.config), "status")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        for label in ("M1", "M4", "V1", "V4", "T1", "T4"):
            self.assertIn(label, result.stdout)
        self.assertIn("127.0.0.1:10000", result.stdout)
        self.assertIn("active", result.stdout)
        values = self.values()
        secrets = [values["VLESS_UUID"]]
        secrets.extend(value for key, value in values.items() if "_KEY_" in key or "_URL_" in key)
        for secret in secrets:
            self.assertNotIn(secret, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
