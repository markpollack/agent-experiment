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

## Review responses (slice-2 review + coordinator sync)

- **Q1 — variant/arm in `run.json` (critical seam to ACT).** Cost-weighted `V(EXPLORE)` is per-arm, so
  the journal must carry the arm label or ACT is forced back onto the result-store join we're retiring.
  `openItem` now writes `config.variant` (the arm), plus `model`, `session`, `itemId`, `itemSlug` — and
  `variant`/`itemId`/`session` tags. Variant is threaded from `ActiveSession.variantName()`; non-session
  runs use the sentinel `"default"`. `(variant, item, runId)` is now recoverable **from the journal
  alone**. `run.json` = serialized journal-core `RunData`; `config` serializes as a flat object
  (`@JsonValue Map`), so ACT reads `run.json.config.variant`.
- **Q2 — concurrency.** Items run **sequentially** within an `AgentExperiment.run()` (a plain for-loop;
  `invokeWithTimeout` blocks on `future.get()`). The real parallel surface is **across runs** — a sweep
  runs arms (separate `run()` calls) concurrently, each reconfiguring the process-global `Journal`.
  `ExperimentJournalConcurrencyTest` runs 8 arms at once on distinct storages and asserts each arm's
  `analysis.jsonl` has exactly its own tool ids (no cross-run leakage) + its own `run.json` variant.
  The lock covers only the configure→start→restore swap, not `recordPhase`/`finish` (which run on the
  per-run bound storage) — so big sweeps don't serialize on journaling writes.
- **Q3 — stable `run.json` schema + A4 final.** ACT can pin: `id` (runId), `config.{variant,itemId,
  itemSlug,model,session?}`, `experimentId`, `startTime`/`endTime`, `tags`. Glob is final:
  `**/journal/experiments/*/runs/*/analysis.jsonl`.
- **A5 (additive refresh).** agent-journal re-shipped 1.6.0-SNAPSHOT so each `events.jsonl`/
  `analysis.jsonl` opens with `{"@type":"header","schemaVersion":1,"stream":...,"runId":...}`. Picked up
  with `mvn -U`, **no code change** — the recorder emits it. Tests now filter to `@type=="step_cost"`
  and assert the header. Lesson: a moving SNAPSHOT can change emitted output without an API change —
  raw-jsonl test parsers must be tolerant of additive header/event kinds.
- **Canonical DESIGN is read-only to stewards.** The A4 convention refinement is **surfaced to the
  contract owner to record**, not edited into the canonical doc by this slice. (An earlier direct A4
  edit was reverted.)

## Acceptance

`AgentExperimentJournalTest` — vanilla run leaves `analysis.jsonl` with `StepCostEvent`s keyed by
tool_use id (`OUTPUT_TOKEN_PROPORTIONAL`, summing to the run total), `events.jsonl` with the same
ids, zero invoker changes; opt-out, no-output-dir, session-variant-in-run.json, and A5-header paths
covered. `ExperimentJournalConcurrencyTest` — 8 concurrent arms, no cross-run leakage.

## Follow-ups (downstream slices)

- Template (slice 2-twin): wire canonical journal on-by-default; update DESIGN/ROADMAP/VISION.
- agent-control-theory (slice 4): consume `**/journal/experiments/*/runs/*/analysis.jsonl`; may
  filter/down-weight `attributionMethod != OUTPUT_TOKEN_PROPORTIONAL`.
- v4: adopt the lifecycle; point ETL at the canonical schema.
