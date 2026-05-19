# agent-experiment

## Build
- Multi-module Maven project: `experiment-core` (abstractions + runner) and `experiment-claude` (Claude SDK impl)
- Build: `./mvnw test` or `./mvnw verify`
- Java 17

## Architecture
- Shared experiment infrastructure (dataset, result, store, comparison, scoring) is domain-neutral — no agent-specific imports
- Agent-specific code lives in `agent`, `runner`, `scoring.JudgmentContextFactory`, `diagnostic`, `pipeline` packages
- The coupling point between shared and agent-specific is `ItemResult.executionDetail` (marker interface `ExecutionDetail`)
- `InvocationResult` implements `ExecutionDetail` for agent experiments

## Conventions
- Records with Builder pattern for complex data types
- `@Nullable` from jspecify for nullable fields
- Factory methods on records (e.g. `InvocationResult.completed()`, `.error()`, `.timeout()`)
- Atomic file writes in ResultStore (temp + move)

## Key Packages
- `io.github.markpollack.experiment.result` — ExperimentResult, ItemResult, ExecutionDetail
- `io.github.markpollack.experiment.agent` — AgentInvoker, InvocationResult, InvocationContext
- `io.github.markpollack.experiment.runner` — AgentExperiment (orchestrates agent experiments)
- `io.github.markpollack.experiment.store` — ResultStore, FileSystemResultStore
- `io.github.markpollack.experiment.comparison` — ComparisonEngine
- `io.github.markpollack.experiment.scoring` — VerdictExtractor, JudgmentContextFactory
- `io.github.markpollack.experiment.diagnostic` — EfficiencyEvaluator, DefaultEfficiencyEvaluator
