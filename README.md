# Agent Experiment

Agent Experiment is the Java lifecycle for repeatable AI-agent evaluations. It loads
versioned dataset items, provisions an isolated workspace for each item, invokes an agent,
evaluates the outcome with an Agent Judge jury, and persists results that can be compared or
re-evaluated without rerunning the agent.

The framework records the experiment and dataset Git revisions, dirty state, configuration,
knowledge-manifest hashes, agent cost and token totals, preserved workspaces, and per-item Agent
Journal traces. Those records make runs auditable and comparable; exact replay still depends on
pinning the external model, agent CLI, tools, and network services used by the invoker.

See the [Agent Experiment documentation](https://lab.pollack.ai/projects/agent-experiment) and
[release history](https://lab.pollack.ai/docs/agent-experiment/whats-new). The
[Agent Experiment Template](https://github.com/markpollack/agent-experiment-template) is the
consumer-shaped starting point for a complete experiment project.

## Lifecycle

1. A `DatasetManager` loads active, versioned items and their before/reference material.
2. A `WorkspaceProvisioner` copies a fixture or checks out a Git `beforeRef` into an isolated
   workspace.
3. An `AgentInvoker` runs the candidate agent with the configured prompt, model, and timeout.
4. An Agent Judge `Jury` evaluates the resulting workspace and execution evidence.
5. `ResultStore`, optional session/sweep stores, and Agent Journal persist results, traces, costs,
   and reproducibility metadata for comparison and post-hoc re-evaluation.

Item-level invocation or judging failures become failed item results so the remaining dataset can
continue. Result-store failures stop the run. Items execute sequentially within one run; separate
sweep arms may execute concurrently, with journal configuration isolated by the framework.

## Modules

| Module | Purpose |
|---|---|
| `experiment-core` | Dataset, workspace, invocation, judging, result/session/sweep stores, comparison, diagnostics, and re-evaluation |
| `experiment-claude` | Claude Code SDK invoker, plan generation, and Claude-backed semantic judging |
| `experiment-workflow` | Base invokers that adapt Agent Workflow executions and journaled step metrics to the experiment lifecycle |

## Maven usage

Agent Experiment 0.6 requires Java 21. Add only the modules your experiment needs:

```xml
<dependency>
    <groupId>io.github.markpollack</groupId>
    <artifactId>experiment-core</artifactId>
    <version>0.6.0</version>
</dependency>
```

Use `experiment-claude` for direct Claude Code SDK invocation and `experiment-workflow` for a
workflow-backed invoker; all three modules use the same version.

From a clean checkout, run the complete credential-free build with:

```bash
./mvnw clean verify
```

The live Claude journal seam test is intentionally excluded from the normal build because it
requires the Claude CLI and can incur model cost. Its exact opt-in command and prerequisites are
documented in `JournalCaptureLiveIT`.

For repeatable local vulnerability analysis, prepare a Dependency-Check database and run the local
profile without database updates:

```bash
./mvnw -Powasp verify -DautoUpdate=false
```

## Maturity and compatibility

Agent Experiment is a released, pre-1.0 framework: the core lifecycle, stores, comparisons,
journals, Claude integration, and workflow adapter have automated coverage, while APIs may still
change between minor versions. Version 0.6 uses Agent Judge 0.14's normalized `Judgment` and complete
composite-attempt model. Persisted `ItemResult` verdicts and judge execution details use
Agent Experiment-owned `RecordedVerdict` and `RecordedJudgment` values so stored results no longer
depend directly on Agent Judge implementation types.

Results written by Agent Experiment 0.5 with Agent Judge 0.13 remain readable. On read, Boolean
scores use their judgment status, ranged numerical scores are normalized to `[0.0, 1.0]`,
categorical values become labels, and legacy `subVerdicts` become synthetic named composite
attempts. Re-saving writes the 0.6 normalized format; obsolete range bounds, category allow-lists,
and unavailable legacy composite names/policies cannot be reconstructed. Malformed legacy ranges
or unknown score shapes fail explicitly during loading instead of being silently reinterpreted.

## License

The current source and artifacts are licensed under the project-specific
[Business Source License 1.1](LICENSE) terms.
