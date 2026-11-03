#!/usr/bin/python3
"""Local protocol fixture for the installed one-read caller. Never executes a provider."""
import json
import os
from pathlib import Path
import sys
import uuid

if sys.argv[1:] == ["--version"]:
    print("codex-cli 0.166.0")
    raise SystemExit(0)
if sys.argv[1:] == ["login", "status"]:
    print("Logged in using ChatGPT")  # Synthetic fixture classification only.
    raise SystemExit(0)

cwd = Path.cwd()
assert sys.argv[1:] == ["--no-daemon", "--ask-for-approval", "never", "--sandbox", "read-only",
                       "-C", str(cwd), "exec", "--ignore-user-config", "--model", "gpt-6-luna",
                       "-c", 'model_reasoning_effort="high"', "--skip-git-repo-check", "--json", "--", "-"]
assert "OPENAI_API_KEY" not in os.environ and "CODEX_API_KEY" not in os.environ
prompt = sys.stdin.buffer.read().decode("utf-8")
assert prompt.startswith("Read ./readme.txt once")
answer = (cwd / "readme.txt").read_text().split("Marker: ")[1].strip()
with (Path(os.environ["TMPDIR"]) / "fixture-launches.jsonl").open("a") as stream:
    stream.write(json.dumps({"pid": os.getpid(), "argv": sys.argv, "prompt": prompt}) + "\n")
native_id, turn_id = str(uuid.uuid4()), str(uuid.uuid4())
template = Path(__file__).with_name("codex-read-native-template.jsonl").read_text()
template = template.replace("11111111-1111-4111-8111-111111111111", native_id)
template = template.replace("22222222-2222-4222-8222-222222222222", turn_id)
template = template.replace("/synthetic/codex-workspace", str(cwd))
records = [json.loads(line) for line in template.splitlines()]
usage = {"input_tokens": 10, "cached_input_tokens": 2, "cache_write_input_tokens": 0,
         "output_tokens": 3, "reasoning_output_tokens": 0}
for record in records:
    payload = record["payload"]
    if record["type"] == "turn_context":
        payload.update(model="gpt-6-luna", effort="high")
    if record["type"] == "response_item":
        payload["content"][0]["text"] = prompt if payload["role"] == "user" else answer
    if payload.get("type") == "task_complete":
        payload["last_agent_message"] = answer
sessions = Path(os.environ["CODEX_HOME"]) / "sessions"
sessions.mkdir(parents=True, exist_ok=True)
(sessions / ("rollout-fixture-" + native_id + ".jsonl")).write_text(
    "".join(json.dumps(record) + "\n" for record in records))
for event in [{"type": "thread.started", "thread_id": native_id}, {"type": "turn.started"},
              {"type": "item.completed", "item": {"id": "item_0", "type": "agent_message", "text": answer}},
              {"type": "turn.completed", "usage": usage}]:
    print(json.dumps(event), flush=True)
