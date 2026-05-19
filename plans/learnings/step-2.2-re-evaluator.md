# Step 2.2: ReEvaluator

> Completed: 2026-05-18

## What Was Created

**`ReEvaluator`** in `reeval` package — re-scores stored experiment results with a new Jury without re-invoking the system under test.

- Builder pattern: `.resultStore()`, `.contextFactory()`
- `agentDefaults(ResultStore)` convenience factory
- `reEvaluate(ExperimentResult, Jury)` — iterates items, delegates to context factory, applies jury
- `reEvaluate(String experimentId, Jury)` — load-then-reEvaluate convenience
- Skipped items: `reEvaluationSkipped=true` + reason
- Re-evaluated items: `reEvaluated=true`, `systemReinvoked=false`, `reEvaluationJury`
- Preserves original `costUsd` and `totalTokens`
- Persists result via ResultStore

## Tests (7)

1. Re-evaluate changes scores (pass → fail with failing jury)
2. Skipped items preserved (failed items get skip metadata)
3. Metadata records provenance (experiment + item level)
4. Load-by-ID convenience works
5. Missing ID throws IllegalArgumentException
6. Custom lambda context factory
7. Re-evaluated result persisted to ResultStore
8. Original cost preserved

## Key Decisions

- `jury.getClass().getSimpleName()` for jury name metadata (Jury interface has no `name()` method)
- `totalDurationMs = 0` for re-evaluated results (no system invocation occurred)
- Aggregate scores recomputed from re-evaluated items
