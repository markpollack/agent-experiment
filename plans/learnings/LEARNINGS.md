# Learnings — Compacted

> Last updated: 2026-05-18 (Stage 1 complete)

## Stage 1: Framework Split

**Goal**: Decouple shared experiment infrastructure from agent-specific execution so future experiment types (judge, re-evaluation) can share datasets, results, storage, and comparison without agent dependencies.

**Key outcomes**:
- `ExecutionDetail` marker interface created in `result` package — shared infrastructure stores but never interprets it
- `InvocationResult implements ExecutionDetail` — zero behavior change for existing agent experiments
- `ItemResult.executionDetail` replaces `ItemResult.invocationResult` — the single coupling point is severed
- `ItemResult.toBuilder()` added for Stage 2 (ReEvaluator needs to modify stored results)
- `ExperimentRunner` renamed to `AgentExperiment` — names the domain concept
- `ExperimentConfig` kept generic — already works for any experiment type

**Architectural boundary verified**:
- Shared infrastructure packages (`result`, `dataset`, `store`, `comparison`, `scoring.VerdictExtractor`) have zero agent-specific imports
- Exception: `ResultObjectMapper` imports `InvocationResult` for Jackson deserialization mixin — inherent to polymorphic serialization, addressed in Step 3.3
- Agent-specific consumers (`JudgmentContextFactory`, `EfficiencyEvaluator`, `DiagnosticAnalyzer`) use `instanceof` pattern matching to cast from `ExecutionDetail`

**Jackson strategy**: Deduction-based with `defaultImpl = InvocationResult.class`. Works for single-subtype case. Step 3.3 will add `JudgeExecutionDetail` to the mixin.
