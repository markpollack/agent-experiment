# Agent Experiment 1.0.0

Agent Experiment 1.0 builds on the 1.0 line of the AgentWorks libraries. Each item gets its own jury, a
measurement that was not observed is recorded as unknown rather than zero, and a new `experiment-runtime`
module runs agents through Agent Client and assembles their recordings with Agent Journal.

This release has breaking changes for code written against 0.9.0. They are listed under
[Breaking changes](#breaking-changes).

## Release pins

| Dependency | Version |
|---|---|
| Agent Client | 1.0.0 |
| Agent Journal | 2.0.0 (`experiment-runtime` only) |
| Agent Judge | 1.0.0 |
| Agent Workflow | 1.1.0 |
| Claude Agent SDK (`claude-code-sdk`) | 1.8.0 |

Agent Experiment 0.9.0 pinned Agent Judge 0.17.0, Agent Workflow 0.12.3, Agent Client 0.30.0, Claude Code SDK
1.6.0 and Agent Journal/Capture 1.10.0. `experiment-core` no longer depends on Agent Journal.

## Modules

Five modules are published, two of them for the first time:

- `experiment-core`: datasets, runners, juries, results and re-evaluation.
- `experiment-agent-client` (**new**): `AgentClientInvoker`, which drives any Agent Client provider. It takes a
  `Function<InvocationContext, AgentOptions>`, so that options come from Agent Client.
- `experiment-claude`: semantic scoring only. `SemanticDiffJudge(SemanticDiffJudgeConfig, plan, workspace)` now
  runs through Agent Client.
- `experiment-workflow`: template and Agent Workflow invokers.
- `experiment-runtime` (**new**): canonical acquisition through Agent Client, final assembly of native
  recordings through Agent Journal, Agent Workflow execution, and immutable per-item results.

## What changed for users

- **A fresh jury for every item.** `AgentExperiment` takes a `Function<AgentJudgingInput, Jury>` and builds the
  jury for each item from that item's input. `AgentJudgingInput` carries the goal, workspace, execution time,
  terminal status and metadata.
- **Judges use the Agent Judge 1.0 API.** A judge implements `Judgment judge()` and receives its inputs when it
  is constructed. The workspace-based judges take a workspace supplier, for example
  `new BuildSuccessJudge(input::workspace, "./mvnw compile")` and `new ClassVersionJudge(input::workspace, 65)`.
- **Unknown is not zero.** `ExperimentResult.totalCostUsd()`, `totalTokens()` and `totalDurationMs()`, and
  `ItemResult.costUsd()`, `totalTokens()` and `durationMs()`, are nullable, and `null` means the value was not
  observed. An item that throws keeps the cost and tokens of an invocation that completed. If no invocation
  completed, they are recorded as unknown, not as `0`.
- **A run names its writer.** Pass `-Dexperiment.writer.revision=<your source revision>`, or supply a writer
  identity. Without one, building the result fails with "Set experiment.writer.revision or supply a writer
  identity". Declare the Judge source you ran with `-Dexperiment.judge.revision`.
- **Judge-as-subject experiments.** `JudgeExperiment.builder().candidate(Function<DatasetItem, Judge>)` resolves
  the candidate judge once per row and invokes that same judge. `replicateCount(int)` sets the number of
  replicates.
- **Re-evaluation.** `ReEvaluationContextFactory.create(...)` returns `Optional<AgentJudgingInput>`. It is empty
  when a stored item cannot be reconstructed.
- **Workflow.** `WorkflowInvoker.buildWorkflow(InvocationContext)` returns
  `WorkflowInvoker.Plan(ValidatedWorkflow, StepRegistry, ExecutionCompatibility)`.
- **Untimed execution.** Agent Client invocations can start without a request deadline:
  `AsyncClientInvocationService.startUntimed(...)`. The caller configures Client and its provider for untimed
  execution. Untimed Agent Workflow runs keep their cancellation. A recorded execution allowance of `0` means
  untimed; it is not an unknown value or a measured duration.
- **Native recordings (`experiment-runtime`).**
  - Async Client executions are bound to the exact experiment attempt, and native Client archives to the exact
    invocation.
  - Qualified Codex one-shot archives are admitted.
  - An unqualified native recording is refused, with the outcome Agent Client reported.
  - CLI versions are recorded, not gated. Any Claude Code version on the SDK route is accepted under
    `client-terminal-claude-sdk/1`, and any Codex CLI version on the exec route under `client-terminal-codex-exec/1`.
    The earlier versioned profile names are still accepted when reading.

- **Direct execution, without Agent Workflow (`experiment-runtime`).** Workflow is optional. A subject the
  application invokes itself, such as a plain Claude call, is recorded as direct execution, with no Workflow created
  around it and no Workflow proof.
  - Each `Attempt` states its `ExecutionKind`: `DIRECT`, with no Workflow coordinates, or `WORKFLOW`, with exactly one
    `WorkflowCoordinates`.
  - Each `Row` states its `RowExecution`: `DIRECT`, `WORKFLOW` (exactly one Workflow proof) or `NOT_STARTED`.
  - A missing proof is never read as direct execution, and a direct record that names a Workflow is refused.
  - New entry points: `Attempt.direct(...)`, `Row.direct(...)`, `ClaudeInvocationService.invokeDirect(request,
    attemptNumber)` and `DirectExecution.assess(...)`, which runs the Jury only for a completed execution with its
    workspace retained.
  - `FileSystemResultStore.read(Path)` reads one stored result file with full validation.

## What this fixes

- **Judge-as-subject runs could resolve and invoke the wrong judge.** A run of 2 items × 3 replicates resolved 12
  judges instead of 6, and could invoke a judge other than the one it recorded. Each row now resolves its candidate
  once.
- **A failed item could be recorded as free.** An item that threw was recorded with a cost
  and token count of `0`. It now keeps the observed values or records them as unknown.
- **Returned agent evidence is retained when judging fails.**
- **Recorded identities name what actually ran.** The Journal qualifier and writer identities, the Workflow build
  identity and the default Judge declaration come from the versions of the loaded artifacts. A released build
  records released coordinates, for example `journal-ocsf-writer:2.0.0`. Without `-Dexperiment.judge.revision`, the
  Judge declaration is `released:agent-judge:<version>`, or `snapshot:agent-judge:<version>` for a snapshot.

## Breaking changes

- `AgentExperiment(DatasetManager, Jury, ResultStore, ExperimentConfig)` is replaced by
  `AgentExperiment(DatasetManager, Function<AgentJudgingInput, Jury>, ResultStore, ExperimentConfig)`.
- Agent Judge API changes since 0.17 that affect Experiment code:
  - `Judge.judge(JudgmentContext)` is now `judge()`, and `JudgmentContext` is gone.
  - `Judgment` moved from `judge.result` to `judge.judgment`.
  - The voting strategies moved from `judge.jury` to `judge.voting`.
  - `CascadedJury.Builder.tier(...)` takes a `judge.verdict.RoutingRule`; `TierPolicy` is gone.
  - `SimpleJury.Builder` adds `judge(String name, Judge)`.
- `ClaudeSdkInvoker` and `ClaudeSdkInvokerConfig` are removed. Use `AgentClientInvoker` with an Agent Client
  provider.
- The phase journal is removed: `RunJournal`, `ExperimentJournal`, `JsonFileRunJournal` and `NoOpRunJournal`.
  `ExperimentConfig.journalEnabled(true)` is refused with "Legacy phase writer retired; use experiment-runtime
  canonical acquisition".
- Result totals are nullable (`Double` and `Long`). Code that unboxes them must handle `null`.
- `ReEvaluationContextFactory.create(...)` returns `Optional<AgentJudgingInput>`.
- `WorkflowInvoker.buildWorkflow(...)` returns `WorkflowInvoker.Plan`.
- A run requires a writer identity (`experiment.writer.revision`).
- `experiment-runtime` writes runtime schema 5 only, and `AgentRecords.CURRENT_SCHEMA_VERSION` is now 5. These
  are refused:
  - saving `new Result(4, ...)`;
  - in a schema 5 result, a `NOT_STARTED` row that carries invocations.

  Record such rows as `DIRECT` or `WORKFLOW` instead.
- The schema 4 `Attempt` and `Row` constructors and the `workflowRunId()`, `workflowInvocationId()`,
  `workflowAttemptId()` and `workflowState()` accessors remain for Workflow records. They throw on direct records.
- In stored runtime JSON, an attempt's Workflow coordinates move from top-level `workflowRunId`,
  `workflowInvocationId` and `workflowAttemptId` to `attempt.workflow[0].runId`, `.invocationId` and `.attemptId`.
  A row's `workflowState` becomes `execution` plus `executionState`. Code that reads these fields from raw JSON must
  move, or read through `FileSystemResultStore`.
- Workflow runs are not resumable across Experiment versions. `WorkflowAgentInvoker` identifies its Workflow
  build by the Experiment version, and Workflow refuses a run recorded by another build as an incompatible
  deployment. A run recorded under a `0.10.0` snapshot cannot be resumed on 1.0.0; finish or restart it on the build
  that started it.

## Correction to an earlier release note

The 0.7.2 note scoped Agent Judge's silent-jury defect, where a parallel jury could drop a judge and still return
a verdict, to Agent Experiment 0.7.0 and 0.7.1. Agent Judge's corrected 0.15.2 note places the defect in every
Judge release from 0.11.0 through 0.15.1. Agent Experiment **0.2.0 through 0.7.1** pinned those releases, so jury
results recorded with any of them are suspect. 0.7.2 was the first release on a fixed Judge.

## Compatibility

Requires Java 21. New results are written as Experiment recording version 3, with Judge V6 verdict documents.
`experiment-runtime` writes runtime schema 5. Its schema 4 results still read through `FileSystemResultStore`
with their original meaning: as Workflow or not-started records, never as direct execution. They keep version 4
and are never rewritten.
No stored result is rewritten. `FileSystemResultStore.loadRaw(id)` returns any stored result's original text and
its SHA-256. Typed reads check the recording version; `docs/result-recordings.md` gives the rules for results
written by earlier releases. The normal build needs no credentials. Live provider
tests are opt-in and may incur model cost.

## License

Agent Experiment 1.0.0 is licensed under the project-specific Business Source License terms in the repository
root `LICENSE` file.
