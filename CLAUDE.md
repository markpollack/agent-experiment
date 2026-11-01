# agent-experiment

## Build
- Multi-module Maven project: `experiment-core` (abstractions + runner), `experiment-claude` (Claude SDK impl), `experiment-workflow` (legacy Agent Workflow adapter), and `experiment-runtime` (canonical typed runtime)
- Full reactor: `./mvnw test` or `./mvnw verify`. A `-pl experiment-runtime -am` check covers only the parent and canonical runtime; it does not qualify the full repository.
- Ordinary tests exclude the `integration` tag and opt-in `*IT` provider classes. Keep those exclusions for deterministic checks.
- Follow `RECORDING-PRINCIPLE.md` when changing persisted observations.
- Java 21

## Architecture
- Shared experiment infrastructure (dataset, result, store, comparison, scoring) is domain-neutral — no agent-specific imports
- Exception: `ResultObjectMapper` imports concrete `ExecutionDetail` subtypes for Jackson deserialization
- Agent-specific code lives in `agent`, `runner`, `scoring.JudgmentContextFactory` packages
- `ItemResult.executionDetail` (marker interface `ExecutionDetail`) is the seam between shared and domain-specific
- `InvocationResult implements ExecutionDetail` for agent experiments
- Agent-specific consumers use `instanceof` pattern matching to cast from `ExecutionDetail`
- `ItemResult.toBuilder()` enables re-evaluation (modifying stored results without re-running agents)

## Legacy journaling (experiment-core run-journal lifecycle)
- `AgentExperiment` owns the canonical agent-journal trace: per item it opens one journal `Run` on `JsonFileStorage`, records each returned `PhaseCapture` (auto-emits derived per-step `StepCostEvent`s → `analysis.jsonl`, keyed by tool_use id), and finishes. **Invokers stay dumb** — they only return `PhaseCapture`; never push journaling into invokers.
- On by default when `outputDir` is set; opt out with `ExperimentConfig.Builder.withoutJournal()`. No `outputDir` → WARN + no-op.
- Layout (beside the result store output): `<runDir>/journal/experiments/<exp>/runs/<runId>/{run,events,analysis}.jsonl`. The measurement ETL (agent-control-theory) joins on `run.json` `config`: **`variant`** (the arm/per-arm grouping key; `"default"` for non-session runs), `itemId`, `itemSlug`, `model`, `session?` — recoverable from the journal alone (no result-store re-join).
- agent-journal's `Journal` context is process-global; `ExperimentJournal` brackets+restores it under a lock — don't add code that leaves `Journal.configure(...)` set. Items run sequentially within a run; sweep arms (separate runs) run in parallel — `ExperimentJournalConcurrencyTest` guards isolation.
- A5 (additive): `events.jsonl`/`analysis.jsonl` open with a `{"@type":"header","schemaVersion":1,...}` line. The recorder emits it (no code here); raw-jsonl readers skip it. Re-pull agent-journal with `mvn -U` for additive SNAPSHOT changes.

## Conventions
- Records with Builder pattern for complex data types
- `@Nullable` from jspecify for nullable fields
- Factory methods on records (e.g. `InvocationResult.completed()`, `.error()`, `.timeout()`)
- Atomic file writes in ResultStore (temp + move)

## Dependencies
- POMs are authoritative for declared versions; command-line property overrides change the tested closure and must be reported explicitly.
- The parent legacy modules declare Judge 0.17.0, SDK 1.6.0, Journal/capture 1.10.0, Workflow 0.12.3 and Client 0.30.0.
- `experiment-runtime/pom.xml` separately declares released Judge 0.18.0 and Workflow 0.14.0 plus versioned Client/SDK/Journal acquisition artifacts. Runtime-only checks do not verify the legacy modules' different dependencies.
- Persisted results use Experiment-owned projections; canonical runtime records carry complete producer documents and distinct acquisition/canonical references.

## Key Packages
- `io.github.markpollack.experiment.result` — ExperimentResult, ItemResult, ExecutionDetail, RecordedJudgment, RecordedVerdict
- `io.github.markpollack.experiment.agent` — AgentInvoker, InvocationResult, InvocationContext
- `io.github.markpollack.experiment.runner` — AgentExperiment (orchestrates agent experiments)
- `io.github.markpollack.experiment.runner.workspace` — WorkspaceProvisioner seam, DefaultWorkspaceProvisioner (file fixture), GitWorkspaceProvisioner (checks out `beforeRef`), WorkspaceStrategy
- `io.github.markpollack.experiment.store` — ResultStore, FileSystemResultStore
- `io.github.markpollack.experiment.comparison` — ComparisonEngine
- `io.github.markpollack.experiment.scoring` — VerdictExtractor, JudgmentContextFactory
- `io.github.markpollack.experiment.reeval` — ReEvaluationContextFactory, AgentReEvaluationContextFactory, ReEvaluator
- `io.github.markpollack.experiment.judge` — JudgeExperiment, JudgeScorer, JudgeScorers, JudgeExecutionDetail
- `io.github.markpollack.experiment.journal` — ExperimentJournal, RunJournal (run-journal lifecycle; owns per-item `Run` on `JsonFileStorage`)

## Canonical typed runtime
- `experiment-runtime` provides the canonical agent path; its focused build is `./mvnw -pl experiment-runtime -am verify`.
- It consumes released Workflow 0.14.0/Judge 0.18.0 and explicitly versioned Client/SDK/Journal development artifacts.
- Its records and historical raw reader have no retired PhaseCapture dependency. Legacy modules above retain their existing contracts.
- Client seals acquisition; Experiment calls Journal FinalAssembly once and records distinct acquisition/canonical hashes. Complete usage/cost evidence remains in the canonical sources; unavailable totals are UNKNOWN.
- Per-item Jury factory runs after typed preparation/measurement; see `experiment-runtime/README.md` and the compiling three-stage consumer test.
