# Learnings — First-class journal capture (experiment-core slice 2 of 5)

> 2026-06-27 · feature branch `feature/journal-capture-lifecycle` ·
> canonical contract: `agent-journal/plans/journal-capture-DESIGN.md` (frozen §4 + A1–A4)

## Goal

Make `AgentExperiment` own the run-journal lifecycle so the canonical agent-journal trace (immutable
`events.jsonl` + derived per-step `StepCostEvent` → `analysis.jsonl`) is written for every experiment
run — out of the box, with **zero invoker changes**. Root cause being fixed: experiment-core never
opened a journal `Run`, so per-tool cost (which is *derived*, and the deriver never ran) was silently
never produced.

## What shipped

- New `io.github.markpollack.experiment.journal` package: `RunJournal` (per-item seam),
  `ExperimentJournal` (owns per-run `JsonFileStorage`, opens one `Run` per item),
  `JsonFileRunJournal` (wraps slice-1 production `RunRecorder`), `NoOpRunJournal`.
- `AgentExperiment` opens the experiment journal once per run and records each returned
  `PhaseCapture` per item then `finish()`s — after invocation, before judging. Invoker untouched.
- `ExperimentConfig.journalEnabled` (default-on) + `withoutJournal()` opt-out + `shouldJournal()`.
- agent-journal pinned to **1.6.0-SNAPSHOT** (bare coordinates, no BOM); `journal-core` added as a
  direct `experiment-core` dependency.

## Key discoveries / decisions

- **`JsonFileStorage` dictates the on-disk layout**: `<baseDir>/experiments/<exp>/runs/<runId>/{run,
  events,analysis}` and `runId` is a library-generated UUID (`DefaultRun`). The literal §4 proposal
  `journal/<item>/…` is therefore impossible without bypassing `JsonFileStorage` (which the contract
  forbids). Refined to the journal-core-native experiment→runs layout under `<runDir>/journal/`, with
  item attribution via `run.json` `itemId` config. Recorded upstream as **amendment A4**.
- **The `Journal` context is process-global** (`Journal.configure` storage + `ExperimentRegistry`
  cache; `RunBuilder.start()` binds the *current* global storage into the `Run`). Handled by
  bracketing configure→start→restore under a static lock in `ExperimentJournal.openItem`, and passing
  storage explicitly to `RunRecorder(run, storage)` so nothing downstream reads the global. The
  journal never leaks its storage as the process default; concurrent experiment runs don't race.
- **`ExperimentRegistry.getOrCreate` caches experiments and only persists `experiment.json` on first
  creation** — across runs with the same name (different storage), later storages would miss it. Fix:
  `ExperimentJournal.open` calls `storage.saveExperiment(...)` explicitly so each per-run storage is
  self-contained. Tests `Journal.reset()` in `@AfterEach`.
- **Fail-loud honored by construction**: the default path always hands the production `RunRecorder` a
  durable `JsonFileStorage` (`persistsDerivedEvents()==true`), so the slice-1 check never trips in
  normal use. If it ever did (forced non-durable storage), the `IllegalStateException` propagates;
  ordinary journal IO errors are logged but never demote a good item result.
- **On-by-default needs an output dir**: a durable journal requires somewhere to live. Default-on
  when `outputDir` is set; no `outputDir` → one-line WARN + no-op (announce the degradation). The
  `RunRecorder.finish()` then try-with-resources `close()` sequence is idempotent (`DefaultRun.finish`
  early-returns when terminal), so no double-write.

## Pitfalls avoided

- Don't push journaling into invokers — they stay dumb (return `PhaseCapture`); the acceptance test
  proves it by leaving `MockAgentInvoker` untouched.
- Don't bump only the `claude-code-capture` artifact — the shared parent property drives both it and
  `journal-core`; bumping the property keeps the pair consistent (they release together).
- Slice-1 changes are additive (`stepCosts()`, `fromEvents()`, `persistsDerivedEvents()`,
  `EVEN_SPLIT`, production `RunRecorder`), so the 1.4.0 → 1.6.0-SNAPSHOT bump did not break
  `experiment-claude`/`experiment-workflow` (full reactor green: 459 + 60 + 7).

## Acceptance

`AgentExperimentJournalTest` — vanilla run leaves `analysis.jsonl` with `StepCostEvent`s keyed by
tool_use id (`OUTPUT_TOKEN_PROPORTIONAL`, summing to the run total), `events.jsonl` with the same
ids, zero invoker changes; opt-out and no-output-dir paths covered.

## Follow-ups (downstream slices)

- Template (slice 2-twin): wire canonical journal on-by-default; update DESIGN/ROADMAP/VISION.
- agent-control-theory (slice 4): consume `**/journal/experiments/*/runs/*/analysis.jsonl`; may
  filter/down-weight `attributionMethod != OUTPUT_TOKEN_PROPORTIONAL`.
- v4: adopt the lifecycle; point ETL at the canonical schema.
