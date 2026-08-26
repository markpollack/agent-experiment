# agent-experiment

## Build
- Multi-module Maven project: `experiment-core` (abstractions + runner), `experiment-claude` (Claude SDK impl), and `experiment-workflow` (Agent Workflow adapter)
- Build: `./mvnw test` or `./mvnw verify`
- Java 21

## Architecture
- Shared experiment infrastructure (dataset, result, store, comparison, scoring) is domain-neutral — no agent-specific imports
- Exception: `ResultObjectMapper` imports concrete `ExecutionDetail` subtypes for Jackson deserialization
- Agent-specific code lives in `agent`, `runner`, `scoring.JudgmentContextFactory`, `diagnostic`, `pipeline` packages
- `ItemResult.executionDetail` (marker interface `ExecutionDetail`) is the seam between shared and domain-specific
- `InvocationResult implements ExecutionDetail` for agent experiments
- Agent-specific consumers use `instanceof` pattern matching to cast from `ExecutionDetail`
- `ItemResult.toBuilder()` enables re-evaluation (modifying stored results without re-running agents)

## Journaling (run-journal lifecycle)
- `AgentExperiment` owns the canonical agent-journal trace: per item it opens one journal `Run` on `JsonFileStorage`, records each returned `PhaseCapture` (auto-emits derived per-step `StepCostEvent`s → `analysis.jsonl`, keyed by tool_use id), and finishes. **Invokers stay dumb** — they only return `PhaseCapture`; never push journaling into invokers.
- On by default when `outputDir` is set; opt out with `ExperimentConfig.Builder.withoutJournal()`. No `outputDir` → WARN + no-op.
- Layout (beside the result store output): `<runDir>/journal/experiments/<exp>/runs/<runId>/{run,events,analysis}.jsonl`. The measurement ETL (agent-control-theory) joins on `run.json` `config`: **`variant`** (the arm/per-arm grouping key; `"default"` for non-session runs), `itemId`, `itemSlug`, `model`, `session?` — recoverable from the journal alone (no result-store re-join).
- agent-journal's `Journal` context is process-global; `ExperimentJournal` brackets+restores it under a lock — don't add code that leaves `Journal.configure(...)` set. Items run sequentially within a run; sweep arms (separate runs) run in parallel — `ExperimentJournalConcurrencyTest` guards isolation.
- A5 (additive): `events.jsonl`/`analysis.jsonl` open with a `{"@type":"header","schemaVersion":1,...}` line. The recorder emits it (no code here); raw-jsonl readers skip it. Re-pull agent-journal with `mvn -U` for additive SNAPSHOT changes.

## Conventions
- Records with Builder pattern for complex data types
- `@Nullable` from jspecify for nullable fields
- Factory methods on records (e.g. `InvocationResult.completed()`, `.error()`, `.timeout()`)
- Atomic file writes in ResultStore (temp + move)

## Dependencies
- All dependencies use `io.github.markpollack` groupId (migrated from org.springaicommunity)
- agent-judge-core/exec: 0.15.1. Runtime code uses normalized `Judgment`/`Verdict`; persisted results use Agent Experiment-owned `RecordedJudgment`/`RecordedVerdict` projections. The result mapper reads the legacy 0.5/Judge 0.13 score and `subVerdicts` shapes and writes only the normalized format.
- claude-code-sdk: 1.5.1, package `io.github.markpollack.claude.agent.sdk.*`
- agent-journal (`journal-core` + `claude-code-capture`): 1.8.2. `journal-core` is a direct `experiment-core` dependency (`Journal`, `Run`, `JsonFileStorage`, `StepCostEvent`, `AttributionMethod`).
- agent-workflow: 0.12.1; agent-client: 0.29.3 (`provided` in `experiment-workflow`)

## Key Packages
- `io.github.markpollack.experiment.result` — ExperimentResult, ItemResult, ExecutionDetail, RecordedJudgment, RecordedVerdict
- `io.github.markpollack.experiment.agent` — AgentInvoker, InvocationResult, InvocationContext
- `io.github.markpollack.experiment.runner` — AgentExperiment (orchestrates agent experiments)
- `io.github.markpollack.experiment.runner.workspace` — WorkspaceProvisioner seam, DefaultWorkspaceProvisioner (file fixture), GitWorkspaceProvisioner (checks out `beforeRef`), WorkspaceStrategy
- `io.github.markpollack.experiment.store` — ResultStore, FileSystemResultStore
- `io.github.markpollack.experiment.comparison` — ComparisonEngine
- `io.github.markpollack.experiment.scoring` — VerdictExtractor, JudgmentContextFactory
- `io.github.markpollack.experiment.diagnostic` — EfficiencyEvaluator, DefaultEfficiencyEvaluator
- `io.github.markpollack.experiment.reeval` — ReEvaluationContextFactory, AgentReEvaluationContextFactory, ReEvaluator
- `io.github.markpollack.experiment.judge` — JudgeExperiment, JudgeScorer, JudgeScorers, JudgeExecutionDetail
- `io.github.markpollack.experiment.journal` — ExperimentJournal, RunJournal (run-journal lifecycle; owns per-item `Run` on `JsonFileStorage`)
