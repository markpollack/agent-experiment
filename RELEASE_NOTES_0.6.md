# Agent Experiment 0.6.0

Agent Experiment 0.6 adds workflow-backed invokers and first-class Agent Journal capture while
bringing the project onto the currently released Java 21 AgentWorks integration line.

## Highlights

- Adds the published `experiment-workflow` module, which adapts typed Agent Workflow executions and
  journaled step cost/token metrics to the `AgentInvoker` contract.
- Owns the per-item Agent Journal lifecycle in `AgentExperiment`, including variant, item, model,
  session, tool-use, and derived step-cost evidence.
- Adds Git-backed workspace provisioning from dataset `beforeRef` values while preserving the
  fixture-copy provisioner.
- Records experiment-code and dataset revisions, dirty state, knowledge manifests, results,
  workspaces, and journals for auditable comparisons and re-evaluation.
- Updates to Agent Journal/Capture 1.6.0, Claude Code SDK 1.4.0, Agent Workflow 0.10.0, Agent Client
  0.26.0, and Java 21.
- Adds a root-only CycloneDX 1.6 aggregate SBOM attached to the parent Maven artifact with classifier
  `cyclonedx`, plus consistent BSL license content in packaged artifacts.
- Pins reusable build and release workflows to the approved immutable Build Tools revision and
  removes remote NVD acquisition from ordinary GitHub Actions.

## Compatibility

This release requires Java 21. Agent Judge is intentionally retained at 0.13.0: Agent Judge 0.14
removes the score hierarchy and changes composite verdicts, while Agent Experiment exposes and
persists those structures. That migration will require an explicit public/result-data compatibility
review rather than an unannounced dependency-only change in 0.6.0.

The normal build is credential-free. The opt-in live Claude journal integration test requires a
Claude CLI environment and may incur model cost.

## License

Agent Experiment 0.6.0 is licensed under the project-specific Business Source License terms in the
repository root `LICENSE` file.
