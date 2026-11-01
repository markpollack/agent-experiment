# Typed agent experiment runtime

`io.github.markpollack:experiment-runtime:0.10.0-fresh-v3-qualification-SNAPSHOT` provides Client acquisition,
Journal final assembly, released Workflow execution and immutable per-item results. It uses
Workflow 0.14.0 and Judge 0.18.0; Client, SDK and Journal are explicitly unreleased task dependencies.
Use this module alone for the canonical agent path. The older Experiment modules retain their
historical APIs and dependencies and are not part of this runtime's closure.

Build the selected module and its parent with Java 21:

```sh
./mvnw -pl experiment-runtime -am verify
```

Use the exact task artifacts from the application's qualified Maven repository. A snapshot version
alone does not identify bytes: retain the committed source and JAR/POM hashes with the run.

## Three typed stages

[ThreeStageConsumerExampleTest](src/test/java/io/github/markpollack/experiment/runtime/ThreeStageConsumerExampleTest.java)
is a compiling, executable consumer example with concrete `Prepare`, `Invoke` and `Measure` Steps
and non-null record/list bridges. Domain preparation, prompt expansion and measurement are supplied
by the application. It exercises Workflow's actual durable engine and the real result store; its
provider and coverage numbers are deterministic fixtures, not live measurements.

Production invocation is supplied through the same constructor seam:

```java
var client = new ClaudeInvocationService(cliVersion, nativeProjectsDirectory,
        Duration.ofSeconds(30), AcquisitionAssembly.journal());
```

In the invoke Step, construct a `Request` from the prepared item, expanded prompt, model,
provider budget in milliseconds, a unique new attempt directory and explicit permission choice.
Pass the actual `StepContext` to `client.invoke(request, context)`. The service creates a distinct
acquisition identity, passes full Experiment/Workflow association to Client capture options, checks
the sealed receipt, calls Journal `ClaudeAcquisition.qualify` into a new derivative directory, validates the derivative association and calls Journal `FinalAssembly` once with that qualified manifest. The return is `Outcome`, carrying the
`Invocation`, workspace ownership state and retained evidence directory.

Build `Workflows.define(...).maxDuration(...).then(prepare).then(invoke).then(measure)` and register
those same concrete Step objects in `StepRegistry`. The finite workflow budget includes preparation,
provider time, final measurement and drain. The service caps the provider budget by remaining eligible
time. `WorkflowAdapter.execute(...)` starts a fresh store and returns an `Execution`; only its
`accepted()` output may be treated as completed. Persistence is outside the graph.

Supply a per-item `JudgeRecording.ItemJuryFactory` to `WorkflowAdapter.assess(...)`:

```java
JudgeRecording.ItemJuryFactory factory = (prepared, measured) -> {
    Path workspace = Path.of(prepared.workspace());
    // Construct this item's configured Jury from workspace, prepared.baseline(), and measured.
    return configuredJuryFactory.create(workspace, prepared.baseline(), measured);
};
```

`configuredJuryFactory` is application-owned; there is no global current workspace. The factory runs
after preparation and measurement. The adapter invokes its Jury once and retains complete Judge V6
JSON, its hash and Judge's conclusion. Workflow success can accompany a valid FAIL domain verdict.

## Results and failure recovery

`Identity` contains run/item/variant/replicate plus a validated length-prefixed row ID. `Attempt`
adds distinct acquisition, Workflow run, logical invocation and physical attempt IDs. Durable values
are non-null scalars, records and concrete lists: do not use Path, Instant, Map or nullable fields.
`Measurement.UNKNOWN` carries an empty values list and reason; a measured zero or signed domain
change remains a measured value. Preserve baseline and final raw artifacts and scoped measurements
in your domain result rather than replacing them with a derived total.

`Result(AgentRecords.CURRENT_SCHEMA_VERSION, "AGENT_CANONICAL", ...)` (schema 4) is saved once via `FileSystemResultStore`. `load`, `list` and
`mostRecent` validate current shape and full associations. A result cannot be overwritten. Explicit
`readHistorical` returns unchanged old JSON and its hash as `LEGACY_UNVERIFIED`, without loading
retired capture classes or reinterpreting old Judge documents.

Invocation, process, capture, assembly, Workflow and Judge states are separate. Acquisition-manifest
and canonical-events hashes have explicit subjects. `Evidence.verify(row)` checks retained file
integrity and Workflow/acquisition associations before evidence consumption. It does **not** prove
Journal source correctness; use Journal's independently packaged verification with acquisition and
assembly expectations for that claim.

A `QuarantinedInvocationException` fails the invoke Step when provider drainage is unconfirmed;
no successor measurement may use that workspace. Its `outcome()` is already retained, and
`readOutcome(attemptDirectory)` allows recovery outside the Step. A late `delivery.json` may appear
later; `readDelivery` reads that evidence without promoting it to accepted output or assembling it.
Timeouts that have drained still carry TIMEOUT and cannot become invocation success. An interrupted
Workflow can remain ACTIVE; the adapter never silently resumes it. Retain the workspace and Workflow
store while quarantined. On failed execution, use retained invocation outcomes with the original
prepared identity to call `assess`; it skips the Jury for unaccepted Workflow output and returns an
explicit incomplete row for the immutable save. Do not rerun the provider to repair evidence.

## Qualification evidence

`Invocation.qualifications()` retains zero or one `Qualification`: original Client manifest reference,
QUALIFIED/REFUSED state, qualified directory, manifest and assembly expectation references, full retained
file closure, producer version and error. `acquisitions()` always remains the original Client receipt.
Qualification and assembly are separate from independently verified source correctness. Refusals preserve
available partial files and their hashes; no profile fallback or original mutation is performed.

For a qualified row, independently run Journal's packaged reader with the canonical events, qualification
`directory()`, `--acquisition manifests().getFirst().path()` and
`--assembly assemblyExpectations().getFirst().path()`. Check its actual result before claiming acceptance.
`AcquisitionAssembly.pinnedJournal()` is the explicit already-bound replay route. It never runs as a
fallback after failed fresh qualification. Previous schema3 files remain available through the explicit
raw historical reader; new writes use schema4. The original Invocation constructor is retained as an
overload with an empty qualifications list.

The V3 consumer authors 30 minutes total for prepare/baseline/invoke/measure, caps the provider by
remaining time with measurement/drain reserve, and keeps domain judges' separately configured limits.
