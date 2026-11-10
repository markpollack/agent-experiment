# Agent Experiment

Agent Experiment provides a Java lifecycle for repeatable agent evaluations: versioned datasets,
isolated workspaces, provider-neutral invocation, configured judging, durable results, comparison
and re-evaluation. Java 21 is required.

The current development reactor is `0.10.0-SNAPSHOT`:

| Module | Purpose |
|---|---|
| `experiment-core` | Datasets, agent/judge runners, complete producer recordings, stores and comparisons |
| `experiment-agent-client` | Provider-neutral `AgentClientInvoker` with an `AgentOptions` factory |
| `experiment-claude` | Configured semantic criterion scoring through Agent Client |
| `experiment-workflow` | Current typed Workflow adapters and template hooks |
| `experiment-runtime` | Canonical acquisition, Journal assembly, Workflow proofs and immutable runtime results |

```bash
./mvnw clean verify
```

The POMs pin Judge `0.19.0-SNAPSHOT`, Client `0.32.1-SNAPSHOT`, Claude Code SDK `1.8.0-SNAPSHOT` and
Journal `2.0.0-SNAPSHOT`, plus released Workflow `0.14.0`. The root POM declares the Central snapshot
repository (`https://central.sonatype.com/repository/maven-snapshots/`, `updatePolicy` `always`), so a
plain build resolves the current snapshots with no local installs. Every push to `main` publishes
`0.10.0-SNAPSHOT` there; the `main` commit identifies what a snapshot contains.
Tests use deterministic stubs, retained data and loopback HTTP; live provider tests remain opt-in.

`AgentClientInvoker` accepts `Function<InvocationContext, AgentOptions>` so provider options do not
need a duplicate Experiment configuration type. Bare responses retain their text and explicitly
unknown usage; use the canonical runtime for original-byte acquisition and Journal evidence.

Agent runners configure a fresh `Jury` for each item and then call `vote()` without a universal
producer context. `SemanticDiffJudge` takes its plan, workspace and independent judge-model
configuration before `judge()`. Workflow adapters accept a
`WorkflowInvoker.Plan(ValidatedWorkflow, StepRegistry, ExecutionCompatibility)` and retain the actual
durable snapshot. Workflow completion and provider/subject success remain separate.

See [recording and migration contracts](docs/result-recordings.md),
[canonical runtime examples](experiment-runtime/README.md), and
[the recording principle](RECORDING-PRINCIPLE.md).

The legacy direct SDK invoker/configuration and phase-journal writer APIs are retired. Historical
recordings remain unchanged; current typed readers refuse unsupported formats, and raw readers retain
exact original bytes. Released versions (latest `0.9.0`) keep their own dependency and API contracts;
snapshots from `main` never change a released artifact.

## License

[Business Source License 1.1](LICENSE)
