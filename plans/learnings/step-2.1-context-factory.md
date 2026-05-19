# Step 2.1: ReEvaluationContextFactory Interface

> Completed: 2026-05-18

## What Was Created

1. **`ReEvaluationContextFactory`** — `@FunctionalInterface` in `reeval` package. Returns `Optional<JudgmentContext>` from a stored `ItemResult`.
2. **`AgentReEvaluationContextFactory`** — default implementation for agent experiments. Uses `instanceof InvocationResult` pattern matching to cast from `ExecutionDetail`. Returns `Optional.empty()` for: failed items, null executionDetail, non-InvocationResult detail types.

## Key Decisions

- **Does not delegate to `JudgmentContextFactory`**: The existing factory requires a full `DatasetItem` which isn't available from stored results. `AgentReEvaluationContextFactory` builds the context directly from `InvocationResult` fields (duration, status, metadata) and `ItemResult` fields (workspace path, item slug as goal).
- **`itemSlug` as goal**: For re-evaluation, we use the stored `itemSlug` as the goal since the original `DatasetItem.developerTask()` isn't persisted in `ItemResult`. This is sufficient for judge re-scoring.
- **Jury has no `name()` method**: Will use `jury.getClass().getSimpleName()` in ReEvaluator metadata.
