#!/usr/bin/env python3
"""Inspect or supervise one explicitly authorized installed Codex caller. No implicit retry."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import time

SAFE_ENVIRONMENT = {"HOME", "CODEX_HOME", "PATH", "LANG", "LC_ALL", "TZ", "TMPDIR"}
FORBIDDEN_ENVIRONMENT = {"OPENAI_API_KEY", "CODEX_API_KEY"}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def read_json(path):
    raw = Path(path).read_bytes()
    return json.loads(raw), digest(raw)


def write_new(path, value):
    with Path(path).open("x") as stream:
        json.dump(value, stream, indent=2)
        stream.write("\n")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def checked_ref(ref):
    path = Path(ref["path"])
    require(path.is_absolute() and path.resolve(strict=True) == path and path.is_file(), "Non-ordinary artifact path")
    require(digest(path.read_bytes()) == ref["sha256"], "Artifact hash changed: " + str(path))
    return str(path)


def check_workspace(manifest):
    workspace = Path(manifest["workspace"]).resolve(strict=True)
    require(str(workspace) == manifest["workspace"] and workspace.is_dir(), "Exact workspace required")
    expected = {checked_ref(ref) for ref in manifest["protectedInputs"]}
    actual = {str(p) for p in workspace.rglob("*") if p.is_file()}
    require(expected == actual and not any(p.is_symlink() for p in workspace.rglob("*")), "Workspace inventory changed")
    return workspace


def validate(manifest, output):
    require(manifest.get("kind") == "codex-one-read-launch/1", "Unknown launch manifest")
    require(manifest.get("nativeAuthorization") is False and manifest.get("approvedSubmissions") == 0,
            "Prepared manifest must remain unactivated; use a separate later authorization")
    require(manifest.get("proposedSubmissions") == 1 and manifest.get("generations") == 1
            and manifest.get("retries") == 0 and manifest.get("resume") is False, "One fresh submission required")
    require(manifest.get("wholeRunSeconds") == 240, "One shared 240-second budget required")
    environment = manifest["environment"]
    require(set(environment) == SAFE_ENVIRONMENT and not FORBIDDEN_ENVIRONMENT.intersection(environment),
            "Exact safe environment allowlist required")
    require(all(isinstance(v, str) and "\0" not in v for v in environment.values()), "Invalid safe environment")
    workspace = check_workspace(manifest)
    require(checked_ref(manifest["launcher"]) == str(Path(__file__).resolve()), "Manifest names another launcher")
    checked_ref(manifest["cli"])
    checked_ref(manifest["nativeExecutable"])
    require(output.is_absolute() and output.parent.resolve(strict=True) == output.parent, "Resolved output parent required")
    require(not output.exists() and not output.is_symlink(), "Existing output refuses replay")
    require(not output.is_relative_to(workspace), "Evidence must be outside workspace")
    return environment


def authorize(activation, manifest_sha, output):
    require(activation is not None, "Explicit later Owner activation required before model child")
    require(activation.get("nativeAuthorization") is True and activation.get("approvedSubmissions") == 1,
            "Exactly one approved submission required")
    require(activation.get("manifestSha256") == manifest_sha, "Activation names different manifest bytes")
    require(activation.get("outputDirectory") == str(output), "Activation names different resolved output")
    require(isinstance(activation.get("ownerReference"), str) and activation["ownerReference"].strip(),
            "Named later Owner activation reference required")
    require(isinstance(activation.get("expiresEpochSeconds"), (int, float))
            and activation["expiresEpochSeconds"] > time.time(), "Activation expired")


def check_closure(manifest):
    integration = manifest.get("installedIntegration") or {}
    require(integration.get("status") == "ACCEPTED" and integration.get("originalDecisionVerified") is True
            and integration.get("mismatchedIdentityRejected") is True
            and integration.get("separatePackagedReaderAccepted") is True, "Final original-D installed integration is missing")
    checked_ref(integration["proof"])
    files = {checked_ref(ref) for ref in manifest["artifacts"]}
    classpath = manifest["runtimeClasspath"]
    require(classpath and set(classpath).issubset(files), "Complete installed classpath required")
    command = manifest["command"]
    require(isinstance(command, list) and all(isinstance(v, str) and v for v in command), "Exact non-shell caller argv required")
    require(command[0] == manifest["callerExecutable"] and command[0] in files
            and manifest["callerSource"] in command and manifest["callerSource"] in files, "Unhashed caller executable/source")
    cp_index = command.index("--class-path") if "--class-path" in command else command.index("-cp")
    require(command[cp_index + 1] == os.pathsep.join(classpath), "Caller classpath differs from installed closure")
    return command


def remaining(deadline, ceiling):
    seconds = min(ceiling, deadline - time.monotonic())
    require(seconds > 0, "Shared whole-run deadline exhausted")
    return seconds


def inspect_cli(manifest, environment, deadline):
    cli = manifest["cli"]["path"]
    findings = []
    for name, arguments in (("version", ["--version"]), ("login", ["login", "status"])):
        result = subprocess.run([cli, *arguments], cwd=manifest["workspace"], env=environment, input=b"",
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=remaining(deadline, 15))
        combined = result.stdout + b"\n" + result.stderr
        if name == "version":
            accepted = result.returncode == 0 and combined.strip().decode(errors="replace") == manifest["cliVersionOutput"]
        else:
            accepted = result.returncode == 0 and b"Logged in using ChatGPT" in combined
        findings.append({"check": name, "exit": result.returncode, "accepted": accepted,
                         "classification": "CHATGPT" if name == "login" and accepted else name,
                         "rawOutputRetained": False})
        require(accepted, "Selected CLI " + name + " capability not confirmed; raw output not retained")
    return findings


def group_alive(group):
    # Linux zombies cannot execute or retain pipes. They await reaping, which this process does not own.
    for entry in Path("/proc").iterdir():
        if not entry.name.isdecimal():
            continue
        try:
            fields = (entry / "stat").read_text().rsplit(")", 1)[1].split()
            if int(fields[2]) == group and fields[0] != "Z":
                return True
        except (FileNotFoundError, ProcessLookupError):
            continue
    return False


def cleanup_owned_group(group, deadline):
    if group == os.getpgrp():
        raise RuntimeError("Refusing to signal own/unrelated process group")
    observed = group_alive(group)
    if observed:
        try:
            os.killpg(group, signal.SIGTERM)
        except ProcessLookupError:
            pass
        end = time.monotonic() + min(5, max(0, deadline - time.monotonic()))
        while group_alive(group) and time.monotonic() < end:
            time.sleep(0.02)
        if group_alive(group):
            try:
                os.killpg(group, signal.SIGKILL)
            except ProcessLookupError:
                pass
            end = time.monotonic() + min(2, max(0, deadline - time.monotonic()))
            while group_alive(group) and time.monotonic() < end:
                time.sleep(0.02)
    return {"remainingGroupObserved": observed, "ceased": not group_alive(group),
            "scope": "Owned process group only; escaped processes unqualified"}


def supervise(command, environment, workspace, output, deadline):
    primary, cleanup, code, group = None, None, None, None
    process = None
    with (output / "caller.stdout").open("xb") as stdout, (output / "caller.stderr").open("xb") as stderr:
        try:
            process = subprocess.Popen(command, cwd=workspace, env=environment, start_new_session=True,
                                       stdin=subprocess.DEVNULL, stdout=stdout, stderr=stderr)
            group = process.pid
            code = process.wait(timeout=remaining(deadline - 15, 225))
            if code:
                primary = "Caller exited " + str(code)
        except Exception as failure:
            primary = type(failure).__name__ + ": " + str(failure)
        finally:
            if group is not None:
                try:
                    cleanup = cleanup_owned_group(group, deadline)
                    if code == 0 and cleanup.get("remainingGroupObserved") and primary is None:
                        primary = "Owned group remained after normal caller exit"
                except Exception as failure:
                    cleanup = {"ceased": False, "error": type(failure).__name__ + ": " + str(failure)}
            if process is not None:
                try:
                    process.wait(timeout=max(0.001, min(2, deadline - time.monotonic())))
                except subprocess.TimeoutExpired:
                    pass
    return {"primaryFailure": primary, "callerExit": code, "ownedGroup": group, "cleanup": cleanup,
            "processCreated": process is not None}


def postconditions(output, manifest):
    # This application-owned receipt is emitted only after the independent installed reader ran.
    record, _ = read_json(output / "native-proof.json")
    require(record.get("kind") == "codex-one-read-proof/1", "Missing one-read postconditions")
    require(record.get("readerExit") == 0 and record.get("readerExcludedClientSdkWriter") is True,
            "Separate packaged reader acceptance required")
    for ref in [record["originalDecision"], record["readerDecision"], *record["retainedEvidence"]]:
        require(Path(ref["path"]).is_relative_to(output), "Postcondition evidence escapes owned output")
    original = Path(checked_ref(record["originalDecision"]))
    decoded = Path(checked_ref(record["readerDecision"]))
    require(original.read_bytes() == decoded.read_bytes(), "Reader decision differs from immutable original D")
    require(record.get("nativeIdentityMatched") is True and record.get("acquisitionComplete") is True
            and record.get("sourceVerified") is True, "Original native source/identity acceptance missing")
    require(record["retainedEvidence"], "Native source/archive/reader evidence is missing")
    for ref in record["retainedEvidence"]:
        checked_ref(ref)
    require(record.get("submissions") == 1 and record.get("generations") == 1, "Unexpected native submission/generation count")
    check_workspace(manifest)
    require(record.get("observedResponse") == manifest["expectedResponse"], "Observed read result differs")
    return {"accepted": True, "originalDecisionSha256": digest(original.read_bytes()),
            "readerExit": 0, "costState": record.get("costState", "UNKNOWN")}


def run(manifest_path, output, activation_path=None, execute=False):
    started = time.monotonic()
    manifest, manifest_sha = read_json(manifest_path)  # Hash and parse these same bytes once.
    deadline = started + 240
    output = output.parent.resolve(strict=True) / output.name
    environment = validate(manifest, output)
    if execute:
        activation = read_json(activation_path)[0] if activation_path else None
        authorize(activation, manifest_sha, output)  # Before CLI or model child.
        command = check_closure(manifest)
    inspections = inspect_cli(manifest, environment, deadline)
    if not execute:
        return {"mode": "INSPECT_ONLY", "manifestSha256": manifest_sha, "checks": inspections,
                "nativeAuthorization": False, "modelTasks": 0}
    output.mkdir(mode=0o700)
    write_new(output / "submission.json", {"manifestSha256": manifest_sha, "ownerReference": activation["ownerReference"],
              "reservedSubmissions": 1, "generations": 1, "outputDirectory": str(output), "retries": 0})
    command = [v.replace("{output}", str(output)).replace("{workspace}", manifest["workspace"])
               .replace("{deadlineEpochMs}", str(int((time.time() + max(0, deadline-time.monotonic()))*1000))) for v in command]
    receipt = supervise(command, environment, manifest["workspace"], output, deadline)
    receipt.update({"manifestSha256": manifest_sha, "inspections": inspections, "elapsedSeconds": time.monotonic()-started,
                    "sharedWholeRunSeconds": 240, "reservationRetained": True})
    if receipt["primaryFailure"] is None and (receipt["cleanup"] or {}).get("ceased"):
        try:
            remaining(deadline, 1)
            receipt["postconditions"] = postconditions(output, manifest)
        except Exception as failure:
            receipt["postconditionFailure"] = type(failure).__name__ + ": " + str(failure)
    receipt["accepted"] = bool(receipt.get("postconditions", {}).get("accepted"))
    write_new(output / "guard-receipt.json", receipt)
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--authorization", type=Path)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    try:
        result = run(args.manifest, args.output, args.authorization, args.execute)
        print(json.dumps(result, indent=2))
        return 0 if not args.execute or result.get("accepted") else 1
    except Exception as failure:
        reserved = (args.output / "submission.json").exists()
        print(json.dumps({"state": "GUARD_FAILURE_AFTER_RESERVATION" if reserved else "REFUSED_BEFORE_MODEL_CHILD",
                          "reason": str(failure), "modelTasks": "UNKNOWN" if reserved else 0}))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
