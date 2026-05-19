# Step 1.0: Design Review

> Completed: 2026-05-18

## Type Mapping: Shared Infrastructure vs Agent-Specific

### Shared Infrastructure (experiment-core conceptual boundary)
These types have **zero** InvocationResult references and are fully domain-neutral:

| Package | Types |
|---------|-------|
| `result` | `ExperimentResult`, `ItemResult` (after refactoring), `KnowledgeFileEntry`, `KnowledgeManifest` |
| `dataset` | `Dataset`, `DatasetItem`, `DatasetManager`, `FileSystemDatasetManager`, `DatasetVersion`, `ItemFilter`, `ResolvedItem`, `SourceRef`, `DatasetItemEntry` |
| `store` | `ResultStore`, `FileSystemResultStore`, `ResultObjectMapper`, `ResultIndex`, `ResultStoreException`, session/sweep types |
| `comparison` | `ComparisonEngine`, `DefaultComparisonEngine`, `ComparisonResult`, `ScoreComparison`, `ItemDiff`, `DiffStatus`, `ExperimentSummary` |
| `scoring` | `VerdictExtractor` |

### Agent-Specific (experiment-agent conceptual boundary)
These types reference or are part of the agent invocation domain:

| Package | Types | InvocationResult refs |
|---------|-------|-----------------------|
| `agent` | `InvocationResult`, `AgentInvoker`, `InvocationContext`, `TerminalStatus`, `AgentInvocationException` | Core type + return type |
| `runner` | `ExperimentRunner`, `ExperimentConfig`, `RunLogManager` | Orchestrator + config |
| `scoring` | `JudgmentContextFactory` | Parameter in create() overloads |
| `diagnostic` | `EfficiencyEvaluator`, `DefaultEfficiencyEvaluator` | Parameter in evaluate() |
| `pipeline` | `PipelineAgentInvoker` | AgentInvoker impl |

### Straddling Types (need refactoring)
- **`ItemResult`**: Currently in `result` package (shared) but has `@Nullable InvocationResult invocationResult` field — the single coupling point. After replacing with `@Nullable ExecutionDetail executionDetail`, it becomes fully shared.

## Proposal Verification

### Claim: ComparisonEngine, ResultStore, VerdictExtractor have no InvocationResult references
**VERIFIED** — grep confirms zero matches in `comparison/`, `store/`, and `VerdictExtractor.java`.

### Claim: ~22 files reference InvocationResult
**VERIFIED** — exactly 22 files found: 10 main source, 12 test files.

## InvocationResult Reference Catalogue with Migration Strategy

### Main Source — Must Change

| File | Nature of Reference | Migration Strategy |
|------|--------------------|--------------------|
| `ItemResult.java` | Record field + builder field/method | Replace with `ExecutionDetail` |
| `ExperimentRunner.java` | Creates InvocationResult, passes to ItemResult | Pass as ExecutionDetail (InvocationResult implements it) |
| `JudgmentContextFactory.java` | Parameter in create() overloads | Keep InvocationResult parameter — this is agent-specific code |
| `EfficiencyEvaluator.java` | Parameter in evaluate() | Keep InvocationResult parameter — agent-specific |
| `DefaultEfficiencyEvaluator.java` | Parameter + computeCost() | Keep InvocationResult parameter — agent-specific |
| `PipelineAgentInvoker.java` | AgentInvoker impl returning InvocationResult | No change — agent-specific |
| `AgentInvoker.java` | Return type | No change — agent-specific |
| `AgentInvocationException.java` | Javadoc only | No change |
| `InvocationResult.java` | The type itself | Add `implements ExecutionDetail` |
| `ClaudeSdkInvoker.java` | AgentInvoker impl | No change — agent-specific |

### Test Files — Update Constructors

All 12 test files construct `ItemResult` with `.invocationResult(...)` — change to `.executionDetail(...)`.

## Design Gaps / Adjustments

1. **No `ExperimentResult` import issue**: ExperimentResult was listed in the exploration as importing InvocationResult, but re-verification shows this is only in test helper code, not the type itself.

2. **ResultObjectMapper**: Currently has no special handling for InvocationResult. When ExecutionDetail is introduced, Jackson polymorphic deduction will be needed for round-trip serialization. For Step 1.1, InvocationResult is the only ExecutionDetail subtype, so existing serialization continues to work. Full polymorphic support deferred to Step 3.3.

3. **EfficiencyEvaluator/JudgmentContextFactory keep InvocationResult**: These are agent-specific code that operates on agent execution data. They should keep their InvocationResult parameters — the consumer (ExperimentRunner) will cast from ExecutionDetail to InvocationResult.

4. **ItemResult.toBuilder()**: Does not exist yet — needed by ReEvaluator in Stage 2. Should be added in Step 1.1 per the roadmap.
