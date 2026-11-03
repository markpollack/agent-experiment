import hashlib
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

SOURCE = Path(__file__).resolve().parents[1] / "codex_read_only.py"
SPEC = importlib.util.spec_from_file_location("launcher", SOURCE)
launcher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(launcher)


def ref(path):
    return {"path": str(path.resolve()), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


class GuardTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        self.workspace = self.root / "workspace"
        self.workspace.mkdir()
        self.item = self.workspace / "readme.txt"
        self.item.write_text("Marker: FIXTURE\n")
        self.cli = self.root / "fixture-cli"
        self.cli.write_text("#!/usr/bin/python3\nimport sys\nprint('codex-cli 0.166.0' if '--version' in sys.argv else 'Logged in using ChatGPT')\n")
        self.cli.chmod(0o700)
        self.caller = self.root / "caller.py"
        self.caller.write_text("raise SystemExit(0)\n")
        self.jar = self.root / "fixture.jar"
        self.jar.write_text("local fixture only")
        self.proof = self.root / "installed.json"
        self.proof.write_text("{}")
        self.manifest = {
            "kind": "codex-one-read-launch/1", "nativeAuthorization": False, "approvedSubmissions": 0,
            "proposedSubmissions": 1, "generations": 1, "retries": 0, "resume": False, "wholeRunSeconds": 240,
            "workspace": str(self.workspace), "environment": {k: "fixture" for k in launcher.SAFE_ENVIRONMENT},
            "protectedInputs": [ref(self.item)], "cli": ref(self.cli), "nativeExecutable": ref(self.cli),
            "cliVersionOutput": "codex-cli 0.166.0", "runtimeClasspath": [str(self.jar)],
            "artifacts": [ref(Path(sys.executable).resolve()), ref(self.caller), ref(self.jar)],
            "callerExecutable": str(Path(sys.executable).resolve()), "callerSource": str(self.caller),
            "command": [str(Path(sys.executable).resolve()), "-cp", str(self.jar), str(self.caller)],
            "installedIntegration": {"status": "ACCEPTED", "originalDecisionVerified": True,
                "mismatchedIdentityRejected": True, "separatePackagedReaderAccepted": True, "proof": ref(self.proof)},
            "expectedResponse": "FIXTURE"
        }
        self.manifest["environment"]["PATH"] = "/usr/bin:/bin"
        self.path = self.root / "manifest.json"
        self.output = self.root / "output"
        self.save()

    def tearDown(self):
        self.temp.cleanup()

    def save(self):
        self.path.write_text(json.dumps(self.manifest))

    def activation(self):
        p = self.root / "activation.json"
        p.write_text(json.dumps({"nativeAuthorization": True, "approvedSubmissions": 1,
            "manifestSha256": launcher.digest(self.path.read_bytes()), "outputDirectory": str(self.output),
            "ownerReference": "SYNTHETIC_TEST_ONLY", "expiresEpochSeconds": time.time()+60}))
        return p

    def test_unapproved_refuses_before_any_child(self):
        with patch.object(launcher.subprocess, "run", side_effect=AssertionError("child forbidden")):
            with self.assertRaisesRegex(ValueError, "activation required"):
                launcher.run(self.path, self.output, execute=True)
        self.assertFalse(self.output.exists())

    def test_auth_binds_exact_bytes_output_and_expiry(self):
        activation = json.loads(self.activation().read_text())
        for field, value in [("manifestSha256", "changed"), ("outputDirectory", str(self.root / "other")),
                             ("expiresEpochSeconds", 0), ("approvedSubmissions", 2)]:
            altered = dict(activation, **{field: value})
            with self.subTest(field=field), self.assertRaises(ValueError):
                launcher.authorize(altered, launcher.digest(self.path.read_bytes()), self.output)

    def test_safe_environment_and_changed_artifact_refusal(self):
        self.manifest["environment"]["OPENAI_API_KEY"] = "SYNTHETIC_SENTINEL"
        with self.assertRaisesRegex(ValueError, "allowlist"):
            launcher.validate(self.manifest, self.output)
        del self.manifest["environment"]["OPENAI_API_KEY"]
        self.item.write_text("changed")
        with self.assertRaisesRegex(ValueError, "hash changed"):
            launcher.validate(self.manifest, self.output)

    def test_inspection_uses_allowlist_not_ambient_and_bounded_status(self):
        with patch.dict(os.environ, {"OPENAI_API_KEY": "SYNTHETIC_SENTINEL", "CODEX_API_KEY": "SYNTHETIC_SENTINEL"}):
            result = launcher.run(self.path, self.output)
        self.assertEqual(result["mode"], "INSPECT_ONLY")
        self.assertTrue(all(c["accepted"] for c in result["checks"]))
        self.assertFalse(self.output.exists())
        with self.assertRaisesRegex(ValueError, "deadline exhausted"):
            launcher.inspect_cli(self.manifest, self.manifest["environment"], time.monotonic()-1)

    def test_incomplete_closure_and_classpath_refuse(self):
        self.manifest["installedIntegration"]["originalDecisionVerified"] = False
        with self.assertRaisesRegex(ValueError, "original-D"):
            launcher.check_closure(self.manifest)
        self.manifest["installedIntegration"]["originalDecisionVerified"] = True
        self.manifest["command"][2] = "unreviewed.jar"
        with self.assertRaisesRegex(ValueError, "classpath differs"):
            launcher.check_closure(self.manifest)

    def supervise(self, code, seconds=20):
        self.output.mkdir()
        return launcher.supervise([sys.executable, "-c", code], self.manifest["environment"], str(self.workspace),
                                   self.output, time.monotonic()+seconds)

    def test_normal_parent_exit_still_stops_owned_child(self):
        code = "import subprocess,sys; subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)']); print('parent exits')"
        receipt = self.supervise(code)
        self.assertEqual(receipt["callerExit"], 0)
        self.assertTrue(receipt["cleanup"]["remainingGroupObserved"])
        self.assertTrue(receipt["cleanup"]["ceased"])
        self.assertFalse(launcher.group_alive(receipt["ownedGroup"]))

    def test_timeout_stops_only_owned_group(self):
        unrelated = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"], start_new_session=True)
        try:
            receipt = self.supervise("import time; time.sleep(60)", seconds=15.2)
            self.assertIn("TimeoutExpired", receipt["primaryFailure"])
            self.assertTrue(receipt["cleanup"]["ceased"])
            self.assertIsNone(unrelated.poll())
        finally:
            os.killpg(unrelated.pid, signal.SIGKILL)
            unrelated.wait(timeout=3)

    def test_primary_and_cleanup_failures_are_both_preserved(self):
        with patch.object(launcher, "cleanup_owned_group", side_effect=RuntimeError("synthetic cleanup uncertainty")):
            receipt = self.supervise("raise SystemExit(17)")
        self.assertEqual(receipt["primaryFailure"], "Caller exited 17")
        self.assertIn("synthetic cleanup uncertainty", receipt["cleanup"]["error"])
        self.assertFalse(receipt["cleanup"]["ceased"])

    def test_exit_zero_without_reader_postconditions_is_not_acceptance_and_no_replay(self):
        activation = self.activation()
        with patch.object(launcher, "check_closure", return_value=[sys.executable, str(self.caller)]):
            result = launcher.run(self.path, self.output, activation, execute=True)
        self.assertFalse(result["accepted"])
        self.assertIn("FileNotFoundError", result["postconditionFailure"])
        self.assertTrue((self.output / "submission.json").exists())
        self.assertTrue((self.output / "guard-receipt.json").exists())
        with self.assertRaisesRegex(ValueError, "Existing output"):
            launcher.run(self.path, self.output, activation, execute=True)

    def test_combined_failure_receipt_keeps_reservation(self):
        self.caller.write_text("raise SystemExit(17)\n")
        activation = self.activation()
        with patch.object(launcher, "check_closure", return_value=[sys.executable, str(self.caller)]), \
             patch.object(launcher, "cleanup_owned_group", side_effect=RuntimeError("synthetic cleanup uncertainty")):
            result = launcher.run(self.path, self.output, activation, execute=True)
        self.assertEqual(result["primaryFailure"], "Caller exited 17")
        self.assertIn("synthetic cleanup uncertainty", result["cleanup"]["error"])
        self.assertTrue((self.output / "submission.json").exists())
        self.assertFalse(result["accepted"])

    def test_inspection_stages_share_remaining_deadline(self):
        observed = []
        def local_status(argv, **kwargs):
            observed.append(kwargs["timeout"])
            time.sleep(0.01)
            text = b"codex-cli 0.166.0" if "--version" in argv else b"Logged in using ChatGPT"
            return subprocess.CompletedProcess(argv, 0, text, b"")
        with patch.object(launcher.subprocess, "run", side_effect=local_status):
            launcher.inspect_cli(self.manifest, self.manifest["environment"], time.monotonic()+0.1)
        self.assertLess(observed[1], observed[0])
        self.assertLessEqual(observed[0], 0.1)

    def test_mismatched_decision_and_reader_are_rejected(self):
        self.output.mkdir()
        d, other = self.output / "d.json", self.output / "reader-d.json"
        d.write_text('{"executionId":"original"}')
        other.write_text('{"executionId":"foreign"}')
        receipt = {"kind": "codex-one-read-proof/1", "readerExit": 0, "readerExcludedClientSdkWriter": True,
            "originalDecision": ref(d), "readerDecision": ref(other), "retainedEvidence": [],
            "nativeIdentityMatched": True, "acquisitionComplete": True, "sourceVerified": True,
            "submissions": 1, "generations": 1, "observedResponse": "FIXTURE"}
        (self.output / "native-proof.json").write_text(json.dumps(receipt))
        with self.assertRaisesRegex(ValueError, "differs from immutable"):
            launcher.postconditions(self.output, self.manifest)
        other.write_bytes(d.read_bytes())
        receipt["readerDecision"] = ref(other)
        receipt["readerExit"] = 4
        (self.output / "native-proof.json").write_text(json.dumps(receipt))
        with self.assertRaisesRegex(ValueError, "reader acceptance"):
            launcher.postconditions(self.output, self.manifest)


if __name__ == "__main__":
    unittest.main()
