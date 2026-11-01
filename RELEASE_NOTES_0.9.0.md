# Agent Experiment 0.9.0

Agent Experiment 0.9 stops re-deriving what a verdict says and records the answer instead. Agent
Judge 0.17 reads its own verdicts; this release stores that reading beside each result and deletes
the code that used to reconstruct it.

## Highlights

- Pins Agent Judge to the released **0.17.0**, and adopts its verdict interpretation: every item now
  records an `interpretation` beside its verdict, taken at record time from the live verdict.
- Removes the local re-implementation of verdict semantics — the determination walk, the copied
  requirement table, the disposition string matching, the decision and seat predicates, and the
  historical read package. **651 lines of production code removed for 191 added.**
- `ItemAccounting` now maps a reading onto a measurement rather than deriving the reading. The
  counting rules are unchanged and remain this project's own: an abstention counts against the
  subject, a not-applicable criterion leaves the denominator, an unassessed subject is the
  instrument's failure and not the subject's, and nothing is defaulted to a pass or a failure.
- Two counting policies are now named rather than implied: a reading the record's own facts
  **contradict** is never counted, and a reading those facts could not **confirm** still is.
- Adds `InterpretationReExport`, which adds an interpretation to results written before the reading
  existed. It parses as a tree, compares every verdict to the one it read before writing anything,
  and is idempotent by schema version.
- Pins Agent Workflow to **0.12.3**, its Agent Judge 0.17.0 compatibility release, for
  `experiment-workflow`.
- Retains the three published modules: `experiment-core`, `experiment-claude`, and
  `experiment-workflow`.

## What this fixes

An item whose jury **errored** was recorded as a subject failure. It is an instrument failure: the
jury could not assess the subject, so the subject's outcome is unknown rather than bad. Across the
stored corpus this affected 46 stored records — 23 distinct evaluations, each written to two paths.

The same class of error is removed at its source: consumers read `interpretation.reading` instead of
inferring an outcome from a verdict's shape.

## Compatibility

Requires Java 21. The public experiment APIs and the persisted-result projection are preserved, with
one addition: `ItemResult` gains a nullable `interpretation`.

**Results written before this release remain readable and are not rewritten by it.** An item whose
verdict carries no interpretation is reported as *unattestable* rather than being interpreted here —
an outcome derived from the verdict's shape is exactly what this release removes. Use
`InterpretationReExport` to add readings to an existing archive; it preserves each stored verdict
exactly, and formatting of non-ASCII may change where a file was written by an older serializer.

The normal build is credential-free. The opt-in live Claude journal integration test requires a
Claude CLI environment and may incur model cost.

## License

Agent Experiment 0.9.0 is licensed under the project-specific Business Source License terms in the
repository root `LICENSE` file.
