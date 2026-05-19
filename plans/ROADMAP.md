# Roadmap: agent-experiment

> **Created**: 2026-05-18T18:00-04:00
> **Last updated**: 2026-05-18T18:00-04:00
> **Proposals**: `experiment-framework-split.md`, `re-evaluator.md`, `judge-experiment.md`

## Overview

This roadmap implements three proposals from the DLAI spiritual port research. The work is ordered by dependency: Stage 1 extracts shared experiment infrastructure and decouples `ItemResult` from `InvocationResult` (the framework split). Stage 2 adds `ReEvaluator` and `ReEvaluationContextFactory` for post-hoc re-scoring. Stage 3 adds `JudgeExperiment` as a sibling typed experiment API. Each stage builds on the shared infrastructure established in Stage 1. The project already has a working build, test infrastructure, and quality tooling, so we skip the template's scaffolding steps and begin with the framework split.

Stage 1 (Phase 0) creates the seam for typed experiment APIs; Stages 2-3 (Phase 1) productize them. `ExecutionDetail` is not part of the shared experiment language — it is an escape hatch for domain-specific per-item details that shared infrastructure stores but does not interpret.

> **Before every commit**: Verify ALL exit criteria for the current step are met — especially the standard items (see [Step Exit Criteria Convention](#step-exit-criteria-convention)). Do NOT remove exit criteria to mark a step complete — fulfill them.

## Key Design Decisions (do not reopen)

- Parallel typed experiment APIs, not `ExperimentTarget<I,O>`
- `ExecutionDetail` marker interface replaces `@Nullable InvocationResult` in `ItemResult`
- `ReEvaluationContextFactory` returns `Optional<JudgmentContext>` (`@FunctionalInterface`)
- `JudgeScorer` receives `JudgeScoringInput(DatasetItem, Judgment, String)`
- `JudgeExperimentResult` wraps `ExperimentResult` (composition, not inheritance)
- Conceptual module boundaries first; physical Maven module split deferred

---

## Stage 1: Framework Split

*Proposal: `experiment-framework-split.md`*

Extracts shared experiment infrastructure from agent-specific execution. The single coupling point is `ItemResult.invocationResult` — replaced with an `ExecutionDetail` marker interface. `ExperimentRunner` is renamed to `AgentExperiment`. No behavior changes to existing agent experiments.

### Step 1.0: Design Review

**Entry criteria**:
- [x] Read: `plans/inbox/experiment-framework-split.md`
- [x] Read: `plans/inbox/re-evaluator.md`
- [x] Read: `plans/inbox/judge-experiment.md`

**Work items**:
- [x] REVIEW current package structure against the proposal's "Conceptual Module Shape"
- [x] MAP existing types to shared-infrastructure vs. agent-specific categories
- [x] VERIFY `ComparisonEngine`, `ResultStore`, `VerdictExtractor` have no `InvocationResult` references (proposal claims they don't)
- [x] IDENTIFY all `InvocationResult` references that must change (22 files per grep)
- [x] DOCUMENT any design gaps or adjustments

**Exit criteria**:
- [x] Design reviewed — mapping of types to conceptual boundaries documented
- [x] All 22 `InvocationResult` references catalogued with migration strategy
- [x] Create: `plans/learnings/step-1.0-design-review.md`
- [x] Update `CLAUDE.md` with distilled learnings
- [x] Update `ROADMAP.md` checkboxes

---

### Step 1.1: ExecutionDetail Marker Interface

**Entry criteria**:
- [x] Step 1.0 complete
- [x] Read: `plans/learnings/step-1.0-design-review.md`

**Work items**:
- [x] CREATE `ExecutionDetail` marker interface in `experiment-core` result package
- [x] REFACTOR `ItemResult`: replace `@Nullable InvocationResult invocationResult` field with `@Nullable ExecutionDetail executionDetail`
- [x] UPDATE `ItemResult.Builder` accordingly
- [x] ADD `ItemResult.toBuilder()` method (needed by `ReEvaluator` in Stage 2)
- [x] UPDATE all `InvocationResult` references in `ItemResult` consumers:
  - `ExperimentRunner.runItem()` — pass `InvocationResult` as the `ExecutionDetail`
  - `JudgmentContextFactory` — cast `ExecutionDetail` to `InvocationResult`
  - `DefaultEfficiencyEvaluator` / `EfficiencyEvaluator` — cast as needed
  - `ResultObjectMapper` — update Jackson polymorphic deserialization for `ExecutionDetail`
- [x] UPDATE all test files that construct `ItemResult` with `invocationResult`
- [x] VERIFY: `./mvnw test` passes

**Exit criteria**:
- [x] `ExecutionDetail` marker interface exists
- [x] `ItemResult.executionDetail()` replaces `ItemResult.invocationResult()`
- [x] `InvocationResult` implements `ExecutionDetail`
- [x] All existing tests pass unchanged (except field name)
- [x] All tests pass: `./mvnw test`
- [x] Create: `plans/learnings/step-1.1-execution-detail.md`
- [x] Update `CLAUDE.md` with distilled learnings
- [x] Update `ROADMAP.md` checkboxes
- [x] COMMIT

**Deliverables**: `ItemResult` decoupled from `InvocationResult` via `ExecutionDetail`

---

### Step 1.2: AgentExperiment Rename

**Entry criteria**:
- [x] Step 1.1 complete
- [x] Read: `plans/learnings/step-1.1-execution-detail.md`

**Work items**:
- [x] RENAME `ExperimentRunner` → `AgentExperiment` (new class, or rename in place)
- [x] UPDATE `ExperimentConfig` if needed (or keep as-is if it's already generic)
- [x] UPDATE all references to `ExperimentRunner` in source and tests
- [x] VERIFY: `./mvnw test` passes

**Exit criteria**:
- [x] `AgentExperiment` is the public entry point for agent experiments
- [x] `ExperimentRunner` no longer exists (or is a deprecated alias)
- [x] All tests pass: `./mvnw test`
- [x] Create: `plans/learnings/step-1.2-agent-experiment-rename.md`
- [x] Update `CLAUDE.md` with distilled learnings
- [x] Update `ROADMAP.md` checkboxes
- [x] COMMIT

**Deliverables**: `AgentExperiment` as named entry point

---

### Step 1.3: Stage 1 Consolidation

**Entry criteria**:
- [ ] All Stage 1 steps complete
- [ ] Read: all `plans/learnings/step-1.*` files

**Work items**:
- [ ] COMPACT learnings from Stage 1 steps into `plans/learnings/LEARNINGS.md`
- [ ] UPDATE `CLAUDE.md` with distilled learnings from the full stage
- [ ] VERIFY shared infrastructure types have no agent-specific imports
- [ ] VERIFY `ExperimentResult`, `ResultStore`, `ComparisonEngine` work with `ExecutionDetail` (not `InvocationResult`)

**Exit criteria**:
- [ ] `LEARNINGS.md` updated with compacted Stage 1 summary
- [ ] Create: `plans/learnings/step-1.3-stage1-summary.md`
- [ ] Update `CLAUDE.md` with distilled learnings
- [ ] Update `ROADMAP.md` checkboxes
- [ ] COMMIT

---

## Stage 2: ReEvaluator

*Proposal: `re-evaluator.md`*

Adds post-hoc re-scoring of stored experiment results without re-invoking the system under test. Two types: `ReEvaluator` (orchestrator) and `ReEvaluationContextFactory` (context reconstruction strategy). `AgentReEvaluationContextFactory` provides default context reconstruction for agent experiment results.

### Step 2.0: Stage 2 Entry — Context Load

**Entry criteria** *(inter-stage gate)*:
- [ ] Stage 1 consolidation complete — Read: `plans/learnings/step-1.3-stage1-summary.md`
- [ ] Read: `plans/learnings/LEARNINGS.md`
- [ ] Read: `plans/inbox/re-evaluator.md`

**Work items**:
- [ ] REVIEW Stage 1 learnings for anything that affects ReEvaluator design
- [ ] VERIFY `ItemResult.toBuilder()` exists (prerequisite from Step 1.1)
- [ ] VERIFY `ResultStore` load/save contract supports re-evaluated results
- [ ] DOCUMENT any scope changes

**Exit criteria**:
- [ ] Stage 1 context loaded; no blocking issues
- [ ] Create: `plans/learnings/step-2.0-stage2-entry.md`
- [ ] Update `ROADMAP.md` checkboxes

**Deliverables**: Verified entry into Stage 2

---

### Step 2.1: ReEvaluationContextFactory Interface

**Entry criteria**:
- [ ] Step 2.0 complete
- [ ] Read: `plans/learnings/step-2.0-stage2-entry.md`

**Work items**:
- [ ] CREATE `ReEvaluationContextFactory` `@FunctionalInterface` in `experiment-core` reeval package
  - `Optional<JudgmentContext> create(ItemResult item)`
- [ ] CREATE `AgentReEvaluationContextFactory` in agent package
  - Default implementation for agent experiment results
  - Returns `Optional.empty()` for failed items or missing `ExecutionDetail`
  - Delegates to existing `JudgmentContextFactory` for context reconstruction
- [ ] WRITE unit tests for `AgentReEvaluationContextFactory`:
  - Successful reconstruction from stored `AgentExecutionDetail`
  - `Optional.empty()` for failed items
  - `Optional.empty()` for null `executionDetail`
- [ ] VERIFY: `./mvnw test` passes

**Exit criteria**:
- [ ] `ReEvaluationContextFactory` interface exists as `@FunctionalInterface`
- [ ] `AgentReEvaluationContextFactory` passes all unit tests
- [ ] All tests pass: `./mvnw test`
- [ ] Create: `plans/learnings/step-2.1-context-factory.md`
- [ ] Update `CLAUDE.md` with distilled learnings
- [ ] Update `ROADMAP.md` checkboxes
- [ ] COMMIT

**Deliverables**: Context reconstruction SPI and default agent implementation

---

### Step 2.2: ReEvaluator

**Entry criteria**:
- [ ] Step 2.1 complete
- [ ] Read: `plans/learnings/step-2.1-context-factory.md`

**Work items**:
- [ ] CREATE `ReEvaluator` in `experiment-core` reeval package
  - Builder pattern with `resultStore` and `contextFactory`
  - `agentDefaults(ResultStore)` convenience factory
  - `reEvaluate(ExperimentResult, Jury)` — iterates items, delegates to context factory, applies jury, builds new `ExperimentResult`
  - `reEvaluate(String experimentId, Jury)` — load-then-reEvaluate convenience
  - Skipped items carry `reEvaluationSkipped=true` metadata with reason
  - Re-evaluated items carry `reEvaluated=true`, `systemReinvoked=false`, `reEvaluationJury` metadata
  - Preserves original `costUsd` (re-evaluation judge cost not tracked in v1)
- [ ] WRITE unit tests:
  - Re-evaluate a stored result with a new jury — scores change
  - Skipped items preserved correctly (failed items, missing detail)
  - Metadata records re-evaluation provenance (`reEvaluatedFrom`, `systemReinvoked=false`)
  - Load-by-ID convenience works
  - Custom `ReEvaluationContextFactory` via lambda
- [ ] VERIFY: `./mvnw test` passes

**Exit criteria**:
- [ ] `ReEvaluator` passes all unit tests
- [ ] Re-evaluated results persist via `ResultStore`
- [ ] All tests pass: `./mvnw test`
- [ ] Create: `plans/learnings/step-2.2-re-evaluator.md`
- [ ] Update `CLAUDE.md` with distilled learnings
- [ ] Update `ROADMAP.md` checkboxes
- [ ] COMMIT

**Deliverables**: Working `ReEvaluator` with agent defaults and custom context factory support

---

### Step 2.3: Stage 2 Consolidation

**Entry criteria**:
- [ ] All Stage 2 steps complete
- [ ] Read: all `plans/learnings/step-2.*` files

**Work items**:
- [ ] COMPACT learnings into `plans/learnings/LEARNINGS.md`
- [ ] UPDATE `CLAUDE.md` with distilled learnings
- [ ] VERIFY re-evaluation → comparison pipeline works end-to-end (unit test)

**Exit criteria**:
- [ ] `LEARNINGS.md` updated with Stage 2 summary
- [ ] Create: `plans/learnings/step-2.3-stage2-summary.md`
- [ ] Update `CLAUDE.md` with distilled learnings
- [ ] Update `ROADMAP.md` checkboxes
- [ ] COMMIT

---

## Stage 3: JudgeExperiment

*Proposal: `judge-experiment.md`*

Adds `JudgeExperiment` as a sibling typed experiment API alongside `AgentExperiment`. The judge is the system under test, evaluated against labeled datasets using `JudgeScorer`. Results flow through shared infrastructure (`ExperimentResult`, `ResultStore`, `ComparisonEngine`).

### Step 3.0: Stage 3 Entry — Context Load

**Entry criteria** *(inter-stage gate)*:
- [ ] Stage 2 consolidation complete — Read: `plans/learnings/step-2.3-stage2-summary.md`
- [ ] Read: `plans/learnings/LEARNINGS.md`
- [ ] Read: `plans/inbox/judge-experiment.md`

**Work items**:
- [ ] REVIEW Stage 2 learnings for anything that affects JudgeExperiment
- [ ] VERIFY `ExecutionDetail` marker interface supports `JudgeExecutionDetail` (implements, not extends)
- [ ] VERIFY `ExperimentResult` and `ResultStore` work with judge-typed results
- [ ] DOCUMENT any scope changes

**Exit criteria**:
- [ ] Stage 2 context loaded; no blocking issues
- [ ] Create: `plans/learnings/step-3.0-stage3-entry.md`
- [ ] Update `ROADMAP.md` checkboxes

**Deliverables**: Verified entry into Stage 3

---

### Step 3.1: JudgeScorer and Supporting Types

**Entry criteria**:
- [ ] Step 3.0 complete
- [ ] Read: `plans/learnings/step-3.0-stage3-entry.md`

**Work items**:
- [ ] CREATE `JudgeScorer` `@FunctionalInterface` in judge package
  - `JudgeScorerResult score(JudgeScoringInput input)`
- [ ] CREATE `JudgeScoringInput` record: `(DatasetItem item, Judgment actual, String expectedLabel)`
- [ ] CREATE `JudgeScorerResult` record: `(boolean match, double score, String reasoning)`
- [ ] CREATE `JudgeScorers` factory with built-in implementations:
  - `exactVerdictMatch()` — PASS/FAIL status match
  - `exactCategoryMatch()` — CategoricalScore value match
  - `numericalTolerance(double tolerance)` — NumericalScore within tolerance
- [ ] CREATE `JudgeExecutionDetail` record implementing `ExecutionDetail`:
  - `(Judgment candidateJudgment, String expectedLabel, JudgeScorerResult scorerResult)`
- [ ] WRITE unit tests for all built-in scorers:
  - Exact verdict match/mismatch
  - Category match/mismatch
  - Numerical within/outside tolerance
  - Non-numeric expected label handling
- [ ] VERIFY: `./mvnw test` passes

**Exit criteria**:
- [ ] All scorer types compile and pass tests
- [ ] `JudgeExecutionDetail` implements `ExecutionDetail`
- [ ] All tests pass: `./mvnw test`
- [ ] Create: `plans/learnings/step-3.1-judge-scorer.md`
- [ ] Update `CLAUDE.md` with distilled learnings
- [ ] Update `ROADMAP.md` checkboxes
- [ ] COMMIT

**Deliverables**: Judge scoring SPI, built-in scorers, execution detail type

---

### Step 3.2: JudgeExperiment and JudgeExperimentResult

**Entry criteria**:
- [ ] Step 3.1 complete
- [ ] Read: `plans/learnings/step-3.1-judge-scorer.md`

**Work items**:
- [ ] CREATE `JudgeExperiment` in judge package
  - Builder: `name`, `candidate(Judge)`, `dataset`, `input(Function<DatasetItem, JudgmentContext>)`, `expected(Function<DatasetItem, String>)`, `scorer(JudgeScorer)`, `resultStore`
  - `run()` → iterates dataset, invokes candidate judge, scores against expected, builds `ExperimentResult` with `JudgeExecutionDetail`, saves via `ResultStore`, returns `JudgeExperimentResult`
  - Metadata: `experimentType=judge`, `candidateJudge`, `scorer`
- [ ] CREATE `JudgeExperimentResult` record wrapping `ExperimentResult`
  - `agreementRate` — fraction of items where judge agreed with expected
  - `disagreements` — list of `JudgeDisagreement(itemId, JudgeExecutionDetail)`
  - `from(ExperimentResult)` factory method
  - `asExperimentResult()` for `ComparisonEngine` / `ResultStore` compatibility
- [ ] CREATE `JudgeDisagreement` record
- [ ] WRITE unit tests:
  - Run judge experiment with mock judge — verify agreement rate
  - Disagreements list populated for mismatched items
  - `ExperimentResult` metadata contains judge experiment type
  - `JudgeExecutionDetail` stored in each `ItemResult`
  - Result persists via `ResultStore`
  - `ComparisonEngine.compare()` works across two `JudgeExperimentResult`s
- [ ] VERIFY: `./mvnw test` passes

**Exit criteria**:
- [ ] `JudgeExperiment.run()` produces correct `JudgeExperimentResult`
- [ ] Results persist and compare via shared infrastructure
- [ ] All tests pass: `./mvnw test`
- [ ] Create: `plans/learnings/step-3.2-judge-experiment.md`
- [ ] Update `CLAUDE.md` with distilled learnings
- [ ] Update `ROADMAP.md` checkboxes
- [ ] COMMIT

**Deliverables**: Working `JudgeExperiment` with typed result and shared infrastructure integration

---

### Step 3.3: Jackson Polymorphic Serialization for ExecutionDetail

**Entry criteria**:
- [ ] Step 3.2 complete
- [ ] Read: `plans/learnings/step-3.2-judge-experiment.md`

**Work items**:
- [ ] UPDATE `ResultObjectMapper` to handle polymorphic `ExecutionDetail` deserialization
  - `InvocationResult` (agent) and `JudgeExecutionDetail` (judge) must round-trip through JSON
- [ ] WRITE tests:
  - Serialize/deserialize `ItemResult` with `InvocationResult` as `ExecutionDetail`
  - Serialize/deserialize `ItemResult` with `JudgeExecutionDetail` as `ExecutionDetail`
  - Load a stored agent result, load a stored judge result — both deserialize correctly
- [ ] VERIFY: `./mvnw test` passes

**Exit criteria**:
- [ ] Both `ExecutionDetail` subtypes round-trip through `ResultStore`
- [ ] All tests pass: `./mvnw test`
- [ ] Create: `plans/learnings/step-3.3-jackson-polymorphic.md`
- [ ] Update `CLAUDE.md` with distilled learnings
- [ ] Update `ROADMAP.md` checkboxes
- [ ] COMMIT

**Deliverables**: Polymorphic serialization supporting all `ExecutionDetail` types

---

### Step 3.4: Stage 3 Consolidation and Final Review

**Entry criteria**:
- [ ] All Stage 3 steps complete
- [ ] Read: all `plans/learnings/step-3.*` files
- [ ] Read: `plans/learnings/LEARNINGS.md`

**Work items**:
- [ ] COMPACT learnings into `plans/learnings/LEARNINGS.md`
- [ ] UPDATE `CLAUDE.md` with distilled learnings from all three stages
- [ ] VERIFY end-to-end: agent experiment → re-evaluate with improved jury → compare (unit test)
- [ ] VERIFY end-to-end: judge experiment → compare two judge versions (unit test)
- [ ] VERIFY cross-type comparison: `ComparisonEngine` can compare agent vs. agent and judge vs. judge results
- [ ] TRIAGE `plans/inbox/` — archive completed proposals

**Exit criteria**:
- [ ] `LEARNINGS.md` updated with full project summary
- [ ] All end-to-end flows verified
- [ ] All tests pass: `./mvnw verify`
- [ ] Create: `plans/learnings/step-3.4-final-summary.md`
- [ ] Update `CLAUDE.md` with distilled learnings
- [ ] Update `ROADMAP.md` checkboxes
- [ ] COMMIT

---

## Plans Directory Structure

```
plans/
├── ROADMAP.md                          # This file
├── inbox/                              # Source proposals from research session
│   ├── experiment-framework-split.md
│   ├── re-evaluator.md
│   └── judge-experiment.md
├── archive/                            # Completed proposals (after Stage 3)
└── learnings/
    ├── LEARNINGS.md                    # Tier 1: Compacted summary
    ├── step-1.0-design-review.md
    ├── step-1.1-execution-detail.md
    ├── step-1.2-agent-experiment-rename.md
    ├── step-1.3-stage1-summary.md
    ├── step-2.0-stage2-entry.md
    ├── step-2.1-context-factory.md
    ├── step-2.2-re-evaluator.md
    ├── step-2.3-stage2-summary.md
    ├── step-3.0-stage3-entry.md
    ├── step-3.1-judge-scorer.md
    ├── step-3.2-judge-experiment.md
    ├── step-3.3-jackson-polymorphic.md
    └── step-3.4-final-summary.md
```

---

## Conventions

### Commit Convention

```
Step X.Y: Brief description of what was done
```

### Step Entry Criteria Convention

Every step's entry criteria must include:
```markdown
- [ ] Previous step complete
- [ ] Read: `plans/learnings/step-{{PREV}}-{{topic}}.md`
```

### Step Exit Criteria Convention

Every step's exit criteria must include:
```markdown
- [ ] All tests pass: `./mvnw test`
- [ ] Create: `plans/learnings/step-X.Y-topic.md`
- [ ] Update `CLAUDE.md` with distilled learnings
- [ ] Update `ROADMAP.md` checkboxes
- [ ] COMMIT
```

### Inter-Stage Gate Convention

First step of Stage N (N > 1) must gate on:
```markdown
- [ ] Stage N-1 consolidation complete — Read: `plans/learnings/step-X.K-stageN-1-summary.md`
- [ ] Read: `plans/learnings/LEARNINGS.md`
```

---

## Revision History

| Timestamp | Change | Trigger |
|-----------|--------|---------|
| 2026-05-18T18:00-04:00 | Initial draft from research proposals | HANDOFF-IMPLEMENTATION.md |
