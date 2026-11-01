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

POMs declare released Judge `0.18.0` and Workflow `0.14.0`, plus local development Client, SDK and
Journal artifacts. Install the exact producer candidates in an isolated Maven repository before
building this development line. A same-named SNAPSHOT alone does not identify tested bytes.
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
exact original bytes. Published pre-1.0 releases have their own dependency/API contracts; this
unpublished development line does not change an already published artifact.

## License

[Business Source License 1.1](LICENSE)
