# Step 3.4: Stage 3 Consolidation and Final Review

> Completed: 2026-05-18

## End-to-End Verification

### Agent experiment → re-evaluate → compare
Verified in `ReEvaluatorTest.reEvaluationToComparisonPipeline`:
1. Create agent experiment result with InvocationResult
2. ReEvaluator re-scores with new jury
3. ComparisonEngine compares original vs re-evaluated

### Judge experiment → compare two versions
Verified in `JudgeExperimentTest.comparisonEngineWorksAcrossTwoJudgeResults`:
1. Run JudgeExperiment v1 (always-pass judge)
2. Run JudgeExperiment v2 (always-fail judge)
3. ComparisonEngine compares v2 vs v1

### Cross-type verification
- ComparisonEngine operates on scores only — works with any ExperimentResult regardless of ExecutionDetail type
- ResultStore serializes/deserializes both ExecutionDetail subtypes via Jackson deduction
- Both verified with 509 tests, 0 failures (`./mvnw verify`)

## Final Architecture

```
experiment-core/
  result/       ExecutionDetail, ItemResult, ExperimentResult     [shared]
  dataset/      Dataset, DatasetItem, DatasetManager              [shared]
  store/        ResultStore, FileSystemResultStore                 [shared]
  comparison/   ComparisonEngine, DefaultComparisonEngine          [shared]
  scoring/      VerdictExtractor                                   [shared]
  reeval/       ReEvaluator, ReEvaluationContextFactory            [shared]
  agent/        AgentInvoker, InvocationResult                     [agent-specific]
  runner/       AgentExperiment, ExperimentConfig                  [agent-specific]
  judge/        JudgeExperiment, JudgeScorer, JudgeScorers         [judge-specific]
  diagnostic/   EfficiencyEvaluator, DiagnosticAnalyzer            [agent-specific]
  pipeline/     PipelineAgentInvoker                               [agent-specific]

experiment-claude/
  agent/claude/    ClaudeSdkInvoker                                [claude-specific]
  scoring/claude/  SemanticDiffJudge                               [claude-specific]
  pipeline/claude/ ClaudePlanGenerator                             [claude-specific]
```
