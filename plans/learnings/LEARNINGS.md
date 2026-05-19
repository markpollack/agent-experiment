# Learnings — Compacted

> Last updated: 2026-05-18 (Stage 2 complete)

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

## Stage 2: ReEvaluator

**Goal**: Enable post-hoc re-scoring of stored experiment results without re-invoking the system under test.

**Key outcomes**:
- `ReEvaluationContextFactory` — `@FunctionalInterface` returning `Optional<JudgmentContext>` from stored `ItemResult`
- `AgentReEvaluationContextFactory` — default implementation using `instanceof InvocationResult` pattern matching
- `ReEvaluator` — orchestrator with builder pattern, `agentDefaults()` convenience, skipped-item metadata, provenance tracking
- Re-evaluation → comparison pipeline verified end-to-end

**Design decisions**:
- `AgentReEvaluationContextFactory` builds context directly from `InvocationResult` fields (does not delegate to `JudgmentContextFactory` since full `DatasetItem` is not available from stored results)
- `jury.getClass().getSimpleName()` for jury name metadata (Jury interface has no `name()` method)
- Preserves original `costUsd`/`totalTokens`; `totalDurationMs = 0` for re-evaluated results

**Dependency migration** (done during Stage 2 entry):
- All dependencies migrated from `org.springaicommunity` → `io.github.markpollack` groupId
- agent-judge: 0.10.0-SNAPSHOT, claude-code-sdk: 1.1.0-SNAPSHOT, claude-code-capture: 1.1.0-SNAPSHOT
