# DESIGN — experiment-core slice: first-class journal capture

> **Status:** implemented 2026-06-27 · **Forge phase-02 design (thin)** — the cross-repo
> contract is owned elsewhere; this records only the experiment-core decisions.

## Canonical contract (source of truth — do not re-derive here)

The end-to-end design and the **frozen §4 interface contract** live in:

`/home/mark/projects/agent-journal/plans/journal-capture-DESIGN.md`

Read it first (esp. §1 root cause — *experiment-core never opens a `Run`* — §4 interfaces, §9
release model, §10 amendments A1–A3, and the new **A4** convention refinement this slice added).
This file is the experiment-core (slice 2 of 5) view; it never restates the contract, only how this
repo honors it.

## Problem this slice fixes

`AgentExperiment` did `invoker.invoke()` → `resultStore.save()` and **never opened a journal
`Run`** — so the derived per-step cost (`StepCostEvent` → `analysis.jsonl`) was never written. The
invoker is the wrong place to fix it (every author would have to remember). The **framework** owns
it now.

## What this slice adds

The framework owns the run-journal lifecycle; **the invoker stays dumb** (it still only returns
`PhaseCapture`).

- **`io.github.markpollack.experiment.journal`** (new package):
  - `RunJournal` — per-item seam: `recordPhase(PhaseCapture)` → `finish()` → `close()`. `noop()` for
    opt-out / no-output-dir.
  - `ExperimentJournal` — owns the per-experiment-run `JsonFileStorage`; opens exactly one journal
    `Run` per dataset item via agent-journal's production `RunRecorder` (slice 1), writing the
    arm/variant + item + model + session into the run `config` (the ETL join surface). Brackets the
    process-global `Journal` reconfigure under a lock and restores it, so the journal never leaks its
    storage as the process default and concurrent experiment runs don't race.
  - `JsonFileRunJournal` / `NoOpRunJournal` — the two `RunJournal` implementations.
- **`AgentExperiment`** opens the experiment journal once per run (`openExperimentJournal`), and per
  item records every returned `PhaseCapture` then finishes (`journalItem`), right after invocation
  and before judging. Journal IO failures are logged but never demote a good item result; the
  slice-1 fail-loud `IllegalStateException` (derived events on non-durable storage) propagates.
- **`ExperimentConfig`** gains `journalEnabled` (`@Nullable Boolean`, default-on) with builder
  `journalEnabled(...)` and the clean `withoutJournal()` opt-out, plus `shouldJournal()`.

## Canonical journal dir convention (refines §4)

agent-journal's `JsonFileStorage` imposes a fixed `experiments/<exp>/runs/<runId>/…` tree under its
base dir, and run ids are library-generated UUIDs. So the slice writes the journal beside the
experiment artifacts as the journal-core-native **experiment → runs** layout:

```
<runDir>/journal/                                  ← JsonFileStorage baseDir (ExperimentJournal.journalRoot)
  experiments/<experimentName>/
    runs/<runId>/
      run.json        ← config carries {variant, itemId, itemSlug, model, session?} + tags; id = runId
      events.jsonl    ← A5 header line, then immutable execution events (LLMCallEvent, ToolCallEvent, …)
      analysis.jsonl  ← A5 header line, then derived StepCostEvents, keyed by tool_use id
```

`<runDir>` is `<outputDir>/<exp>/<experimentId>` (non-session) or
`<outputDir>/<exp>/sessions/<session>/<variant>` (session) — the same artifact dir that already holds
the run log and preserved workspaces (i.e. **beside the ResultStore output**). Discoverable,
config-free: the ETL globs `**/journal/experiments/*/runs/*/analysis.jsonl` and recovers
`(variant, item, runId)` from each run's `run.json` **`config` map alone** — no result-store re-join.
This **refines** the §4 proposal `journal/<item>/…`; the refinement (the experiment→runs layout and
the `run.json` join surface) is **surfaced to the canonical owner to record as amendment A4** — this
slice does not edit the canonical design directly (it is read-only to stewards). The result store is
unchanged — it stays the experiment summary; the journal is the trace source of truth the ETL consumes.

### `run.json` join surface (stable for the measurement ETL / ACT)

`run.json` is the serialized journal-core `RunData`. The fields ACT can rely on:

| Field | Meaning |
|---|---|
| `id` | the run id (the `runs/<runId>` dir name) |
| `config.variant` | **the arm/variant label** cost-weighted `V(EXPLORE)` groups by; `"default"` for non-session single-arm runs |
| `config.itemId` / `config.itemSlug` | the dataset item |
| `config.model` | the model id |
| `config.session` | the sweep session id (absent for non-session runs) |
| `experimentId` | the experiment name (the `experiments/<exp>` dir) |
| `startTime` / `endTime` | run timestamps (ISO-8601) |
| `tags` | mirror of `variant` / `itemId` / `session` for filtering |

So per-arm grouping is `group by config.variant` — **from the journal alone**. The journal `<exp>`
dir is the experiment *name*, not the variant.

### A5 schema header (additive — refreshed 1.6.0-SNAPSHOT)

Each `events.jsonl` / `analysis.jsonl` now opens with a header line
`{"@type":"header","schemaVersion":1,"stream":"events"|"analysis","runId":"…"}`. **No code change on
this side** — the production recorder emits it; the experiment harness inherited it by re-pulling the
refreshed SNAPSHOT (`mvn -U`). Raw-jsonl readers (ACT) skip the leading header; the slice's tests
filter to `@type == "step_cost"` and assert the header's presence.

## On-by-default + opt-out

- **On by default** whenever an `outputDir` is configured (durable journal has somewhere to live).
- **Opt-out** via `ExperimentConfig.Builder.withoutJournal()` for lightweight/judge runs.
- **No `outputDir`** → nothing durable to write; journaling no-ops after a one-line WARN
  (degradation announced — fail-loud spirit). `JudgeExperiment` is unaffected (it has its own path).

## Acceptance (met)

`AgentExperimentJournalTest`: a vanilla run with two tool uses + per-turn usage leaves
`analysis.jsonl` with `StepCostEvent`s keyed by tool_use id (`OUTPUT_TOKEN_PROPORTIONAL`, summing to
the run total), `events.jsonl` carrying the same ids — **with zero invoker changes**
(`MockAgentInvoker` untouched). Also covers: `run.json` carries `variant`/`session`/`model`/`itemId`
for a sweep arm (and `variant="default"` for a non-session run); the A5 schema header opens each
stream; opt-out and no-output-dir paths.

`ExperimentJournalConcurrencyTest` (Q2): 8 arms run **concurrently** (the real surface — items are
sequential within a run, but a sweep runs arms in parallel, each reconfiguring the process-global
`Journal`), each on its own storage, each recording phases with arm-unique tool ids. Asserts every
arm's `analysis.jsonl` contains **exactly** its own step ids (no cross-run event leakage) and its
`run.json` carries its own `variant` — proving the configure→start→restore-under-lock swap binds each
run to its own storage. The lock covers only the brief context swap, not `recordPhase`/`finish`, so
big sweeps don't serialize on journaling writes. Full reactor build green.

## Dependency / release

agent-journal **1.6.0-SNAPSHOT**, pinned bare (no BOM): `claude-code-capture` + `journal-core` via
the parent `claude-code-capture.version` property (SNAPSHOT-first, DESIGN §9 / A3). It is a moving
SNAPSHOT — re-pull with `mvn -U` when agent-journal ships an additive change (e.g. **A5**, the schema
header line, picked up with no code change here). Switch to the BOM-managed release at the single
coordinated feature release. When done, this unblocks the template (slice 2-twin) and v4 adoption
(slice 4).
