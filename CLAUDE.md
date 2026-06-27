# agent-experiment

## Build
- Multi-module Maven project: `experiment-core` (abstractions + runner) and `experiment-claude` (Claude SDK impl)
- Build: `./mvnw test` or `./mvnw verify`
- Java 17

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
- Layout (beside the result store output): `<runDir>/journal/experiments/<exp>/runs/<runId>/{run,events,analysis}.jsonl`; item attribution via `run.json` `itemId` config. See `plans/journal-capture-DESIGN.md`.
- agent-journal's `Journal` context is process-global; `ExperimentJournal` brackets+restores it under a lock — don't add code that leaves `Journal.configure(...)` set.

## Conventions
- Records with Builder pattern for complex data types
- `@Nullable` from jspecify for nullable fields
- Factory methods on records (e.g. `InvocationResult.completed()`, `.error()`, `.timeout()`)
- Atomic file writes in ResultStore (temp + move)

## Dependencies
- All dependencies use `io.github.markpollack` groupId (migrated from org.springaicommunity)
- agent-judge-core/exec: 0.10.0-SNAPSHOT, package `io.github.markpollack.judge.*`
- claude-code-sdk: 1.1.0-SNAPSHOT, package `io.github.markpollack.claude.agent.sdk.*`
- claude-code-capture: 1.1.0-SNAPSHOT, package `io.github.markpollack.journal.*`
- agent-journal (journal-core + claude-code-capture): pinned **1.6.0-SNAPSHOT** (bare coordinates via the parent `claude-code-capture.version` property, no BOM) for the journal-capture feature; SNAPSHOT-first until the coordinated release. `journal-core` is a direct `experiment-core` dependency (`Journal`, `Run`, `JsonFileStorage`, `StepCostEvent`, `AttributionMethod`).

## Key Packages
- `io.github.markpollack.experiment.result` — ExperimentResult, ItemResult, ExecutionDetail
- `io.github.markpollack.experiment.agent` — AgentInvoker, InvocationResult, InvocationContext
- `io.github.markpollack.experiment.runner` — AgentExperiment (orchestrates agent experiments)
- `io.github.markpollack.experiment.store` — ResultStore, FileSystemResultStore
- `io.github.markpollack.experiment.comparison` — ComparisonEngine
- `io.github.markpollack.experiment.scoring` — VerdictExtractor, JudgmentContextFactory
- `io.github.markpollack.experiment.diagnostic` — EfficiencyEvaluator, DefaultEfficiencyEvaluator
- `io.github.markpollack.experiment.reeval` — ReEvaluationContextFactory, AgentReEvaluationContextFactory, ReEvaluator
- `io.github.markpollack.experiment.judge` — JudgeExperiment, JudgeScorer, JudgeScorers, JudgeExecutionDetail
- `io.github.markpollack.experiment.journal` — ExperimentJournal, RunJournal (run-journal lifecycle; owns per-item `Run` on `JsonFileStorage`)
