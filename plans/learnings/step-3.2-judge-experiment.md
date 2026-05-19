# Step 3.2: JudgeExperiment and JudgeExperimentResult

> Completed: 2026-05-18

## What Was Created

1. **`JudgeExperiment`** — sibling typed experiment API alongside AgentExperiment. Builder with `name`, `candidate(Judge)`, `items(List<DatasetItem>)`, `input`, `expected`, `scorer`, `resultStore`. `run()` iterates items, invokes candidate, scores, persists.
2. **`JudgeExperimentResult`** — wraps `ExperimentResult` with `agreementRate` and `disagreements` list. `from()` factory and `asExperimentResult()` for shared infra compatibility.
3. **`JudgeDisagreement`** — record: `(String itemId, JudgeExecutionDetail detail)`

## Scope Adjustments

- **`items(List<DatasetItem>)` instead of `dataset(Dataset)`**: `Dataset` is tied to filesystem loading via `DatasetManager`. Judge datasets are simpler — items provided directly. The `datasetVersion` is an optional builder field.
- **`Verdict.builder()` instead of `Verdict.of()`**: No `of()` factory on Verdict. Used builder with `aggregated` + `individualByName("scorer", judgment)` so VerdictExtractor can extract scores.

## Tests (6)

1. Agreement rate = 1.0 when all match
2. Disagreements populated for mismatches (0.5 rate)
3. Metadata contains experimentType=judge
4. JudgeExecutionDetail in each ItemResult
5. Result persists via ResultStore
6. ComparisonEngine.compare() works across two JudgeExperimentResults
