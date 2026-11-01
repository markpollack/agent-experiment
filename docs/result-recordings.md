# Result recordings and current migration

New `experiment-core` recordings use **Experiment envelope version 3** and released Judge `0.18.0`
**V6** producer documents. These are different version spaces. The canonical `experiment-runtime`
retains its separately versioned format described in its README. No historical file is rewritten.

The former Experiment version 2 writer and Judge portable-v2 interpreter are historical. Current
Judge deliberately refuses those typed producer versions. `FileSystemResultStore.loadRaw(id)` returns
whole original UTF-8 text and SHA-256 without claiming a current measurement view. The named older
unversioned Experiment fixtures retain their raw producer documents and original opaque interpretations;
a derivative labels those readings `LEGACY_UNVERIFIED` and supplies no new comparison scores.
Unknown/current-malformed envelopes refuse typed reads. Historical source/environment/recipes must
remain pinned when running the original analysis.

## Producer source and provenance

`RecordedJudgment` and `RecordedVerdict` retain complete producer JSON text, UTF-8 SHA-256 and external
consumer associations. Current serialization and typed reading use `ResultJson.module()` and
`Verdict.requireUsable()`: preservation bounds, conclusion semantics and whole-root invocation ownership
are checked without executing a supplier, evaluator, policy or model. Root owners, rejected returns,
refused originals, requirements, seats, retained rules and attempts survive intact.

Current conclusion snapshots retain `accountingVersion: experiment-v6-conclusion:v1`, source hash,
producer revision declaration and loaded artifact hash. Original historical interpretations remain
opaque observations; Experiment does not implement a replacement Judge interpreter.

Production writers require `experiment.writer.revision`; callers should also declare exact producer
source via `experiment.judge.revision`. Loaded binaries are always hashed. The default Judge declaration
names released `0.18.0`; source declarations are caller assertions, not archive signatures. Maven tests
supply an explicitly `TEST-ONLY` writer identity for synthetic recordings. A class-directory build is
labeled separately from a JAR. Provenance validation never equates mutable version names with bytes.

## Current accounting

| Producer conclusion | Experiment subject population |
|---|---|
| PASS | PASS |
| FAIL | NON_PASS |
| NOT_APPLICABLE | EXCLUDED |
| INCONCLUSIVE | UNATTESTABLE / unknown |

Judge's INCONCLUSIVE can cover abstention, error or incomplete composition. A root status cannot
recover the distinction. Original detailed producer facts remain available; current accounting does
not guess the old denominator. Instrument health stays UNKNOWN absent explicit observed failure facts.
A thrown/null candidate or recorded roster mismatch independently establishes instrument failure.
Provider completion, finding validity, policy action, capture and consumer persistence remain distinct.

Judge comparison records retain candidate source separately from supplied truth and the named scorer.
Unscored rows do not become disagreements. Agreement is matches/scored comparisons; mean score is
separate. Missing measurement remains absent. Case/replicate and effective input identity govern
comparability; a local case ID alone proves neither equal evidence nor an ordinary candidate delta.

## Native observations and C2 charges

`InvocationRecords.of(wholeVerdict)` resolves root/shared/child/refused owners once. Equal repeated
owners are not additional requests; conflicting portable owners invalidate complete totals. The stored
owner hash includes protocol, completion, model, duration, native facts and artifact references, while
excluding memory-only exceptions.

Recognized `jev-systemone:0.2.0` owner usage uses `inputTokens`/`outputTokens`. The separate protected
transport trace uses snake_case. Duplicated judgment metadata is not another charge. Valid reported
cost requires finite nonnegative value, USD, and exactly
`vercel-gateway-reported:v1:/provider_metadata/gateway/cost`. Explicit zero survives, absent/invalid cost
stays unknown, and no token price rule is invented. Valid reported cost can survive missing token totals
or an ERROR finding. It excludes subject work, evidence preparation and other operations.

Every unsupported native-protocol owner retains an unknown observation. A known scoped subtotal may
remain available while complete totals are absent. On write and reopen, **all** typed usage observations
are recomputed from retained producer owners and compared, followed by row/run totals. Additional
unbound provider rows, altered source/hash/cost/tokens and corresponding altered totals refuse.
Custom observation extractors may retain verified transport metadata/timing/artifacts; they cannot mint
unbound measured usage rows. Other provider billing adapters need an explicit source-bound contract.

Bare Agent Client responses carry `measurementState=UNKNOWN`; their primitive compatibility fields are
not measurements. The runner propagates null item/run totals, retains response text and omits invented
zero token metrics. Canonical acquisition and Journal assembly remain the `experiment-runtime` route.
The old phase writer is retired; an explicit legacy writer request fails at construction.

## Persistence and failure behavior

One writer owns each core store root; overlapping writers fail before publication. Result publication
uses an exclusive hardlink from a completed temporary file, preventing overwrite; the index uses an
atomic replacement. An index failure after publication reports failure and retains the immutable result.
This is not a crash-durable transaction or an automatic index repair guarantee. Raw/exact-ID reads allow
retained evidence recovery. A process killed before final save may lose its in-memory run.

A Judge run makes one final save. Declared populations, returned ERROR, thrown/null calls, cancellation,
unentered rows and partial diagnostics remain explicit. Re-evaluation creates a fresh result ID, retains
original costs as provenance and records newly configured item instruments; skipped rows acquire no new
jury or score. Historical records and session variants cannot be overwritten.

Current deterministic tests exercise actual writer/store/reopen/discovery, historical refusal, whole-V6
ownership, zero/unknown/invalid charges, cost-only ERROR, shared/conflicting/unsupported owners,
coordinated provenance tampering, typed Workflow execution/reopening and failed provider replies. Fake
and retained-data checks establish plumbing and rejection behavior, not live provider qualification.
