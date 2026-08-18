# Agent Experiment

Agent Experiment provides a Java lifecycle for repeatable AI-agent evaluations: load versioned
dataset items, provision isolated workspaces, invoke an agent, evaluate outcomes, and persist
auditable results for comparison and re-evaluation.

**Canonical documentation:** [lab.pollack.ai/projects/agent-experiment](https://lab.pollack.ai/projects/agent-experiment)

Agent Experiment 0.6.0 requires Java 21. Add the modules your experiment needs; all three published
modules use the same version.

```xml
<dependency>
    <groupId>io.github.markpollack</groupId>
    <artifactId>experiment-core</artifactId>
    <version>0.6.0</version>
</dependency>
```

From a clean checkout, run:

```bash
./mvnw clean verify
```

## Maturity and compatibility

Agent Experiment is a released, pre-1.0 framework, so APIs may change between minor versions.
Version 0.6 uses Agent Judge 0.14 while retaining tested readers for Agent Experiment 0.5 / Judge
0.13 result files. Run records support audit and comparison; exact replay still requires pinning
external models, agent CLIs, tools, and services.

## License

[Business Source License 1.1](LICENSE)
