# Roadmap: experiment-core — first-class journal capture (slice 2 of 5)

> **Created**: 2026-06-27T15:00-04:00
> **Last updated**: 2026-06-27T15:00-04:00
> **Design version**: canonical `agent-journal/plans/journal-capture-DESIGN.md` (frozen + A1–A4);
> experiment-core view `plans/journal-capture-DESIGN.md`

## Overview

This roadmap implements the **experiment-core slice** of the cross-repo first-class journal-capture
feature: make `AgentExperiment` own the run-journal lifecycle so journaling is a *property of running
an experiment*, not an opt-in an author must remember (the v3/v4 blind spot). The slice depends only
on slice 1's **frozen §4 interfaces** (agent-journal 1.6.0-SNAPSHOT installed in `~/.m2`), per the
SNAPSHOT-first release model (DESIGN §9 / A3) — no Maven Central release gates the work.

The work is one stage. It pins the SNAPSHOT, adds a small `journal` package that wraps slice 1's
production `RunRecorder`, gives `AgentExperiment` the per-item open→record→finish lifecycle while the
**invoker stays dumb**, adds an on-by-default opt-out, proves the acceptance criterion with tests,
and records the documentation (the repo DESIGN pointer + the §4 convention refinement A4). The result
store is untouched; the journal becomes the trace source of truth the measurement ETL (slice 4)
consumes.

> **Before every commit**: Verify ALL exit criteria for the current step are met — especially the
> standard items (see [Step Exit Criteria Convention](#step-exit-criteria-convention)). Do NOT remove
> exit criteria to mark a step complete — fulfill them.

## Key Design Decisions (do not reopen)

- **Framework owns the lifecycle, invoker stays dumb.** Journaling lives in `AgentExperiment`, never
  in `AgentInvoker`; invokers keep returning `PhaseCapture`. (DESIGN §1 root cause, §4.)
- **One journal `Run` per dataset item**, on durable `JsonFileStorage`, via slice 1's production
  `RunRecorder` (auto-emits `StepCostEvent` → `analysis.jsonl`). No custom recorder.
- **Convention = journal-core-native experiment→runs** under `<runDir>/journal/`, not the literal §4
  `journal/<item>/…` (run ids are library UUIDs; the ETL is built around the experiment/run tree).
  Item attribution via `run.json` `itemId` config. Refinement recorded as canonical **A4**.
- **On by default when `outputDir` is set**; `withoutJournal()` opt-out; no-output-dir → WARN + no-op.
- **Process-global `Journal` context is bracketed and restored** under a lock in `ExperimentJournal`
  — never left reconfigured, never raced across parallel experiment runs.
- **SNAPSHOT-first, bare coordinates, no BOM.** `claude-code-capture` + `journal-core` pinned to
  1.6.0-SNAPSHOT via the parent property; switch to BOM at the single coordinated release (A3).
- **Result store unchanged** — it stays the experiment summary; journal is the trace source of truth.

---

## Stage 1: Run-Journal Lifecycle

### Step 1.0: Gate & Design Review

**Entry criteria**:
- [x] Read: canonical `agent-journal/plans/journal-capture-DESIGN.md` (§1 root cause, §4, §9, A1–A3)
- [x] Read: `plans/inbox/journal-capture-handoff.md`
- [x] **Gate**: slice 1 §4 interfaces frozen and agent-journal **1.6.0-SNAPSHOT** installed in `~/.m2`
      (incl. A1 `EVEN_SPLIT`, `persistsDerivedEvents()`, production `RunRecorder`)

**Work items**:
- [x] VERIFY the slice-1 API surface against installed sources: `Journal`/`Run`/`RunBuilder`,
      `JsonFileStorage` layout, `RunRecorder(run, storage)` + fail-loud, `BaseRunRecorder.recordPhase`,
      `JournalSteps.fromEvents`, `StepCostEvent`, `AttributionMethod`, `PhaseCapture.stepCosts()`
- [x] CONFIRM `AgentExperiment` currently never opens a `Run` (root cause holds in this repo)
- [x] DECIDE the journal dir convention given `JsonFileStorage`'s fixed `experiments/<exp>/runs/<id>`
      layout and UUID run ids → experiment→runs under `<runDir>/journal/` (refines §4 → A4)

**Exit criteria**:
- [x] API surface and convention decided; no blocking gaps in slice 1
- [x] Update `ROADMAP.md` checkboxes

**Deliverables**: Verified gate; design decisions for the slice.

---

### Step 1.1: Pin agent-journal 1.6.0-SNAPSHOT

**Entry criteria**:
- [x] Step 1.0 complete

**Work items**:
- [x] BUMP parent `claude-code-capture.version` → `1.6.0-SNAPSHOT` (drives both `claude-code-capture`
      and `journal-core` dependencyManagement; bare coordinates, no BOM)
- [x] ADD `journal-core` as a direct dependency of `experiment-core` (imports `Journal`, `Run`,
      `JsonFileStorage`, `StepCostEvent`, `AttributionMethod`)
- [x] VERIFY resolution + compile of `experiment-core` against the installed SNAPSHOT

**Exit criteria**:
- [x] `./mvnw -pl experiment-core -am compile` green
- [x] No transitive version conflict introduced for `experiment-claude` / `experiment-workflow`
- [x] Update `ROADMAP.md` checkboxes

**Deliverables**: Build pins the slice-1 SNAPSHOT.

---

### Step 1.2: `journal` package

**Entry criteria**:
- [x] Step 1.1 complete

**Work items**:
- [x] CREATE `RunJournal` (interface: `recordPhase`/`finish`/`close`, `noop()`)
- [x] CREATE `NoOpRunJournal` (singleton no-op) and `JsonFileRunJournal` (wraps `RunRecorder`)
- [x] CREATE `ExperimentJournal` — owns per-run `JsonFileStorage`; `open`/`disabled`/`openItem`/
      `enabled`/`journalRoot`; brackets+restores the global `Journal` context under a lock; writes
      `experiment.json` explicitly (registry-cache-proof)
- [x] CREATE `package-info.java` (`@NullMarked`) documenting the lifecycle + invoker-stays-dumb

**Exit criteria**:
- [x] Package compiles; ArchitectureTest rules still hold (no dep on `experiment.agent.claude`)
- [x] Update `ROADMAP.md` checkboxes

**Deliverables**: Reusable run-journal lifecycle primitives.

---

### Step 1.3: On-by-default opt-out in `ExperimentConfig`

**Entry criteria**:
- [x] Step 1.2 complete

**Work items**:
- [x] ADD `journalEnabled` (`@Nullable Boolean`, default-on) record component + Javadoc
- [x] ADD builder `journalEnabled(...)` and `withoutJournal()`; `shouldJournal()` accessor
- [x] VERIFY only the builder constructs `ExperimentConfig` (no broken direct constructors)

**Exit criteria**:
- [x] `ExperimentConfigTest` green; field defaults to enabled
- [x] Update `ROADMAP.md` checkboxes

**Deliverables**: Clean opt-out switch.

---

### Step 1.4: Wire the lifecycle into `AgentExperiment`

**Entry criteria**:
- [x] Step 1.3 complete

**Work items**:
- [x] OPEN the experiment journal once per run (`openExperimentJournal`): enabled iff
      `shouldJournal()` and a run directory exists; WARN once + disable if requested without `outputDir`
- [x] PER ITEM record every returned `PhaseCapture` then `finish()` (`journalItem`), after invocation
      and before judging; resilient to IO failure (log, don't demote a good result); propagate the
      slice-1 fail-loud `IllegalStateException`
- [x] THREAD the `ExperimentJournal` through `runItem` (no invoker signature change)

**Exit criteria**:
- [x] `AgentExperimentTest` (existing) green — no behavior regression
- [x] Update `ROADMAP.md` checkboxes

**Deliverables**: `AgentExperiment` owns the run-journal lifecycle.

---

### Step 1.5: Acceptance tests + full build

**Entry criteria**:
- [x] Step 1.4 complete

**Work items**:
- [x] WRITE `AgentExperimentJournalTest`:
  - vanilla run → `analysis.jsonl` with `StepCostEvent`s keyed by tool_use id
    (`OUTPUT_TOKEN_PROPORTIONAL`), `events.jsonl` with the same ids, **zero invoker changes**
  - per-step `attributedCostUsd` sums to the run total
  - `withoutJournal()` → no journal written
  - no `outputDir` → run completes, no journal, no exception
- [x] RESET the process-global `Journal` between tests (`@AfterEach`)
- [x] RUN full reactor build

**Exit criteria**:
- [x] All tests pass: `./mvnw test` (experiment-core 459, experiment-claude 60, experiment-workflow 7)
- [x] Acceptance criterion demonstrably met
- [x] Update `ROADMAP.md` checkboxes

**Deliverables**: Proven acceptance; green reactor.

---

### Step 1.6: Documentation

**Entry criteria**:
- [x] Step 1.5 complete

**Work items**:
- [x] CREATE `plans/journal-capture-DESIGN.md` (thin pointer to the canonical contract + slice
      decisions + the journal dir convention)
- [x] REFINE the canonical design's §4 "Canonical journal dir convention" row and add **amendment A4**
      recording the experiment→runs layout
- [x] UPDATE project `CLAUDE.md` with the journaling package + convention

**Exit criteria**:
- [x] Docs coherent; canonical contract remains the single source of truth
- [x] Update `ROADMAP.md` checkboxes

**Deliverables**: Design/roadmap kept coupled and current.

---

### Step 1.K: Consolidation, QA Review & Handoff

**Entry criteria**:
- [x] All Stage 1 steps complete
- [x] Read: all `plans/learnings/journal-capture-*` files from this slice

**Work items**:
- [x] COMPACT learnings into `plans/learnings/journal-capture-slice.md` (discoveries, patterns,
      deviations, pitfalls) and reference from `plans/learnings/LEARNINGS.md`
- [ ] RUN QA review (see [phase review template](~/projects/agento-forge/phases/phase-review-template.md)):
      adversarially check the lifecycle ownership, fail-loud honoring, global-state handling, and the
      convention against the §4 contract; address MUST FIX / SHOULD FIX findings
- [ ] COMMIT on the feature branch; open follow-ups for the template (slice 2-twin) and v4 (slice 4)

**Exit criteria**:
- [x] `LEARNINGS.md` references the slice summary
- [ ] QA review passes (zero MUST FIX findings)
- [ ] Feature branch committed
- [ ] Downstream slices (template, v4) notified that experiment-core is green

**Deliverables**: Consolidated learnings, QA-reviewed slice, downstream unblocked.

---

## Conventions

### Step Exit Criteria Convention

Every step's exit criteria include, beyond step-specific items:

```markdown
- [ ] All tests pass: `./mvnw test`
- [ ] Update `ROADMAP.md` checkboxes
- [ ] (consolidation step) Update `plans/learnings/LEARNINGS.md` + `CLAUDE.md`
- [ ] COMMIT
```

### QA Review Loop

The consolidation step (1.K) runs an adversarial review against the frozen §4 contract and the
acceptance criterion, then addresses findings before the slice is declared done — design↔roadmap stay
coupled (Forge), and the canonical contract is never silently diverged from (changes go to the
canonical doc first, as A4 here).

---

## Revision History

| Timestamp | Change | Trigger |
|-----------|--------|---------|
| 2026-06-27T15:00-04:00 | Initial roadmap; Stage 1 implemented and green | `plans/inbox/journal-capture-handoff.md` |
