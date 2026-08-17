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
- Migrates to Agent Judge 0.14's normalized `Judgment` and complete composite-attempt API, while
  persisting an Agent Experiment-owned result projection instead of Agent Judge implementation
  types.
- Updates to Agent Journal/Capture 1.6.0, Claude Code SDK 1.4.0, Agent Workflow 0.10.0, Agent Client
  0.26.0, Agent Judge 0.14.0, and Java 21.
- Adds a root-only CycloneDX 1.6 aggregate SBOM attached to the parent Maven artifact with classifier
  `cyclonedx`, plus consistent BSL license content in packaged artifacts.
- Pins reusable build and release workflows to the approved immutable Build Tools revision and
  removes remote NVD acquisition from ordinary GitHub Actions.

## Compatibility

This release requires Java 21 and moves to Agent Judge 0.14.0. `ItemResult.verdict()` now returns
`RecordedVerdict`, and `JudgeExecutionDetail.candidateJudgment()` returns `RecordedJudgment`.
Callers constructing results may still pass a live Agent Judge `Verdict` to the `ItemResult`
builder; it is converted immediately to the stable recorded projection.

Existing Agent Experiment 0.5 / Agent Judge 0.13 result files are read automatically. The migration
preserves statuses, reasoning, checks, metadata, normalized numerical meaning, categorical values,
and nested verdict evidence. It is intentionally lossy only where the old model has no normalized
equivalent: Boolean score objects collapse to their authoritative status; numerical min/max and
categorical allow-lists are not re-emitted; and unnamed `subVerdicts` receive synthetic legacy
attempt names because 0.13 stored no composite name, relation, or policy. Saving a loaded result
writes the new normalized format. Invalid numerical ranges and unknown legacy score shapes fail at
load time with an explicit error and must be corrected or upgraded by the producer.

The normal build is credential-free. The opt-in live Claude journal integration test requires a
Claude CLI environment and may incur model cost.

## License

Agent Experiment 0.6.0 is licensed under the project-specific Business Source License terms in the
repository root `LICENSE` file.
