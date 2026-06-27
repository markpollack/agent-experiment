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
    `Run` per dataset item via agent-journal's production `RunRecorder` (slice 1). Brackets the
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
      run.json        ← carries config.itemId / config.itemSlug for item attribution
      events.jsonl    ← immutable execution events (LLMCallEvent, ToolCallEvent, …)
      analysis.jsonl  ← derived StepCostEvents, keyed by tool_use id
```

`<runDir>` is `<outputDir>/<exp>/<experimentId>` (non-session) or
`<outputDir>/<exp>/sessions/<session>/<variant>` (session) — the same artifact dir that already holds
the run log and preserved workspaces (i.e. **beside the ResultStore output**). Discoverable,
config-free: the ETL globs `**/journal/experiments/*/runs/*/analysis.jsonl` and recovers the item
from each run's `itemId` config. This **refines** the §4 proposal `journal/<item>/…` (recorded as
amendment **A4** in the canonical design). The result store is unchanged — it stays the experiment
summary; the journal is the trace source of truth the ETL consumes.

## On-by-default + opt-out

- **On by default** whenever an `outputDir` is configured (durable journal has somewhere to live).
- **Opt-out** via `ExperimentConfig.Builder.withoutJournal()` for lightweight/judge runs.
- **No `outputDir`** → nothing durable to write; journaling no-ops after a one-line WARN
  (degradation announced — fail-loud spirit). `JudgeExperiment` is unaffected (it has its own path).

## Acceptance (met)

`AgentExperimentJournalTest`: a vanilla run with two tool uses + per-turn usage leaves
`analysis.jsonl` with `StepCostEvent`s keyed by tool_use id (`OUTPUT_TOKEN_PROPORTIONAL`, summing to
the run total), `events.jsonl` carrying the same ids — **with zero invoker changes**
(`MockAgentInvoker` untouched). Opt-out and no-output-dir paths covered. Full reactor build green.

## Dependency / release

agent-journal **1.6.0-SNAPSHOT**, pinned bare (no BOM): `claude-code-capture` + `journal-core` via
the parent `claude-code-capture.version` property (SNAPSHOT-first, DESIGN §9 / A3). Re-`install`
agent-journal and rebuild if it changes; switch to the BOM-managed release at the single coordinated
feature release. When done, this unblocks the template (slice 2-twin) and v4 adoption (slice 4).
