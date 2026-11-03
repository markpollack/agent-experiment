# Guarded Codex one-read caller

`codex_read_only.py` is a Linux supervisor for an application-owned, installed Java caller. It does
not implement a provider or a Journal profile. The application manifest supplies the exact installed
classpath/caller artifacts, original short prompt and expected response, protected workspace, safe
environment, and completed original-decision integration proof.

Inspection is the default:

```sh
python3 scripts/codex_read_only.py --manifest /absolute/packet.json --output /absolute/new-output
```

It validates protected inputs and executable hashes, then checks the selected CLI's version and local
ChatGPT login capability in an explicit non-secret environment. Neither API-key variable is inherited;
credential files are not read or copied by this supervisor. Raw login output is never retained. A cached
login is not a successful model task or proof of model access.

Execution additionally requires `--execute --authorization /absolute/activation.json`. The separate
activation must name the exact manifest SHA-256, resolved new output directory, nonempty Owner reference,
future expiry, `nativeAuthorization: true` and exactly one approved submission. The prepared manifest
itself remains false/zero. This is trusted workflow custody, not cryptographic proof of a human identity.
Do not create an activation merely to test with a real model. Deterministic tests use local fixtures.

The manifest and its exact bytes are read once. All runtime JARs, the caller source and executable are
hash checked; the command's classpath must equal the declared closure. The installed integration proof
must report original-D equality, a meaningful foreign-identity refusal, and acceptance by a packaged
reader excluding Client, SDK and writer. Partial or unknown producer closures cannot launch.

A single shared 240-second budget includes validation, bounded version/login checks, the sole caller,
cleanup and postconditions. The caller receives a remaining deadline argument when requested by its
command template. The application must keep Client and reader stages within that budget. The supervisor
owns one new process group, checks it on both normal and failed exit, and sends TERM/KILL only to that
group. Normal caller exit with a remaining executable child is a failed attempt even if cleanup then
succeeds. Escaped-process containment is unqualified. Primary failure and cleanup uncertainty remain
separate in `guard-receipt.json`.

`submission.json` is reserved before any caller process and retained on failure. Existing output refuses
replay. A caller's zero exit is insufficient: `native-proof.json` must retain exact original and
independently decoded decision bytes, complete source/identity verification, packaged-reader success,
one generation/submission and the expected observed response. Linked evidence is hash checked within
the owned output, and protected input hashes are rechecked. Native source, qualification, accounting and
reader semantics remain with their owning libraries. Missing cost defaults to UNKNOWN, never zero.

The wrapper supplies no dollar, token or internal-model-turn bound and makes no new sandbox guarantee.
User rules and the actual native permissions must be stated in the application packet. The readiness
fixture suite does not qualify a live provider, native archive or product result.

Run the complete deterministic guard suite without any provider installed or invoked:

```sh
python3 -m unittest discover -s scripts/tests -v
```

It covers missing/changed/expired activation, input/artifact and classpath changes, key-free inspection,
shared deadlines, normal parent exit leaving a child, timeout without signaling an unrelated group,
combined primary/cleanup failure, no replay, foreign decision rejection and exit-zero without reader
postconditions. Java library code is unchanged by this standalone supervisor.
