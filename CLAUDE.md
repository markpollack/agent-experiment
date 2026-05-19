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

## Key Packages
- `io.github.markpollack.experiment.result` — ExperimentResult, ItemResult, ExecutionDetail
- `io.github.markpollack.experiment.agent` — AgentInvoker, InvocationResult, InvocationContext
- `io.github.markpollack.experiment.runner` — AgentExperiment (orchestrates agent experiments)
- `io.github.markpollack.experiment.store` — ResultStore, FileSystemResultStore
- `io.github.markpollack.experiment.comparison` — ComparisonEngine
- `io.github.markpollack.experiment.scoring` — VerdictExtractor, JudgmentContextFactory
- `io.github.markpollack.experiment.diagnostic` — EfficiencyEvaluator, DefaultEfficiencyEvaluator
- `io.github.markpollack.experiment.reeval` — ReEvaluationContextFactory, AgentReEvaluationContextFactory, ReEvaluator
