# Step 1.3: Stage 1 Consolidation

> Completed: 2026-05-18

## Summary

Stage 1 extracted shared experiment infrastructure from agent-specific execution. Three changes:

1. **ExecutionDetail marker interface** — the seam between shared and domain-specific
2. **ItemResult refactored** — `invocationResult` → `executionDetail`, plus `toBuilder()`
3. **ExperimentRunner → AgentExperiment** — names the domain concept

## Verification Results

### Shared infrastructure — no agent imports
- `result/` — zero agent imports (ExecutionDetail is in result package)
- `dataset/` — zero agent imports
- `comparison/` — zero agent imports
- `store/` — one import: `ResultObjectMapper` needs `InvocationResult` for Jackson mixin (expected, deferred to Step 3.3)
- `scoring/VerdictExtractor` — zero agent imports

### ExperimentResult, ResultStore, ComparisonEngine work with ExecutionDetail
- `ExperimentResult` aggregates `ItemResult` — no direct `ExecutionDetail` access needed
- `ResultStore` serializes/deserializes via Jackson — `ExecutionDetailMixin` handles polymorphism
- `ComparisonEngine` operates on scores — never touches `ExecutionDetail`

### All tests pass
- 417 tests, 0 failures, 0 errors, 0 skipped

## Ready for Stage 2
- `ItemResult.toBuilder()` exists (prerequisite for ReEvaluator)
- `ExecutionDetail` marker interface ready for `JudgeExecutionDetail` (Stage 3)
- Shared infrastructure validated as domain-neutral
