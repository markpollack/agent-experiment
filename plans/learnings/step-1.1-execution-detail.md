# Step 1.1: ExecutionDetail Marker Interface

> Completed: 2026-05-18

## What Changed

1. **Created `ExecutionDetail` marker interface** in `result` package — shared infrastructure stores but does not interpret it
2. **`InvocationResult` implements `ExecutionDetail`** — zero behavior change, just adds interface
3. **`ItemResult` refactored**:
   - Record field: `invocationResult` → `executionDetail` (type: `@Nullable ExecutionDetail`)
   - Builder method: `.invocationResult()` → `.executionDetail()`
   - Added `toBuilder()` method (needed by `ReEvaluator` in Stage 2)
4. **`ExperimentRunner.runItem()`**: passes `InvocationResult` via `.executionDetail()` — works because `InvocationResult implements ExecutionDetail`
5. **`DiagnosticAnalyzer`**: changed from `item.invocationResult()` null-check to `item.executionDetail() instanceof InvocationResult` pattern match — cleaner and type-safe
6. **`ResultObjectMapper`**: added `ExecutionDetailMixin` with Jackson deduction for polymorphic deserialization. `InvocationResult` is the `defaultImpl` — only subtype for now
7. **All test files updated**: `.invocationResult(...)` → `.executionDetail(...)`

## Key Decisions

- **Jackson polymorphic strategy**: Used `@JsonTypeInfo(use = Id.DEDUCTION, defaultImpl = InvocationResult.class)` — same pattern as `Score` hierarchy. This means old JSON with `invocationResult` field name won't deserialize the detail (field is now `executionDetail`), but `FAIL_ON_UNKNOWN_PROPERTIES` is disabled so it won't fail. Full polymorphic support with type discriminator deferred to Step 3.3.
- **Agent-specific consumers keep `InvocationResult` parameter types**: `JudgmentContextFactory`, `EfficiencyEvaluator`, `DefaultEfficiencyEvaluator` — these are agent-specific code that should know the concrete type. The caller (`ExperimentRunner`) casts from the record field.
- **`DiagnosticAnalyzer` uses pattern matching**: `instanceof InvocationResult ir` is cleaner than null-check + cast, and naturally handles future `ExecutionDetail` subtypes by skipping them.

## Gotchas

- `ExperimentRunnerTest` requires clean git state (checks for uncommitted changes) — these tests only pass after commit
