# Agent Experiment 0.7.0

Agent Experiment 0.7 refreshes the Java 21 experiment framework onto the current certified
AgentWorks release line while preserving the experiment, result, and journal contracts introduced
in 0.6.

## Highlights

- Updates Agent Judge to 0.15.0 and Agent Workflow to 0.12.0, including the current workflow-backed
  experiment and journal integration line.
- Updates Agent Journal/Capture to 1.8.0, Claude Code SDK to 1.5.0, and the provided-scope Agent
  Client integration to 0.29.0.
- Advances the repository-controlled Jackson 2 and Jackson 3 BOMs to 2.22.2 and 3.2.2 and refreshes
  compatible stable logging, null-safety, test, build, SBOM, and Central publishing tooling.
- Retains the three published modules: `experiment-core`, `experiment-claude`, and
  `experiment-workflow`.

## Compatibility

This release requires Java 21. It preserves the 0.6 public experiment APIs and persisted-result
projection. Legacy Agent Experiment 0.5 / Agent Judge 0.13 result files remain readable through the
existing compatibility layer.

The normal build is credential-free. The opt-in live Claude journal integration test requires a
Claude CLI environment and may incur model cost.

## License

Agent Experiment 0.7.0 is licensed under the project-specific Business Source License terms in the
repository root `LICENSE` file.
