# Step 3.1: JudgeScorer and Supporting Types

> Completed: 2026-05-18

## What Was Created

1. **`JudgeScorer`** — `@FunctionalInterface` in `judge` package: `JudgeScorerResult score(JudgeScoringInput)`
2. **`JudgeScoringInput`** — record: `(DatasetItem item, Judgment actual, String expectedLabel)`
3. **`JudgeScorerResult`** — record: `(boolean match, double score, String reasoning)`
4. **`JudgeScorers`** — factory with built-in implementations:
   - `exactVerdictMatch()` — PASS/FAIL status match
   - `exactCategoryMatch()` — CategoricalScore value match, falls back to status for non-categorical
   - `numericalTolerance(double)` — NumericalScore within tolerance, handles non-numeric labels
5. **`JudgeExecutionDetail`** — record implementing `ExecutionDetail`: `(Judgment, String, JudgeScorerResult)`

## Tests (10)

- Exact verdict: match, mismatch, case-insensitive
- Category: match, mismatch, non-categorical fallback
- Numerical: within tolerance, outside tolerance, non-numeric label, non-numerical score fallback

## Notes

- `DatasetItem` is a record with no builder — tests use direct constructor
- `JudgeExecutionDetail implements ExecutionDetail` — same pattern as `InvocationResult implements ExecutionDetail`
