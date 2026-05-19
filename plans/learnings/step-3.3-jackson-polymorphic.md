# Step 3.3: Jackson Polymorphic Serialization

> Completed: 2026-05-18

## What Changed

Updated `ResultObjectMapper.ExecutionDetailMixin` to include both `ExecutionDetail` subtypes:
- `InvocationResult` (agent experiments) — discriminated by `status`, `phases` properties
- `JudgeExecutionDetail` (judge experiments) — discriminated by `candidateJudgment`, `expectedLabel`, `scorerResult` properties

Jackson deduction works because the two records have no overlapping property names.

## Tests Added (2 new, 1 existing)

1. **Existing**: `roundTripsItemResultWithInvocationAndVerdict` — agent detail round-trip
2. **New**: `roundTripsItemResultWithJudgeExecutionDetail` — judge detail round-trip with full assertion on nested fields
3. **New**: `bothExecutionDetailSubtypesDeserializeCorrectly` — both types in same test, verifying deduction discriminates correctly

## Notes

- `defaultImpl = InvocationResult.class` preserved — unknown detail structures fall back to InvocationResult deserialization
- The `Score` mixin also uses deduction — consistent pattern across the codebase
