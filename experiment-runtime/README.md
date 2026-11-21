# Typed agent experiment runtime

`io.github.markpollack:experiment-runtime` (version declared in its POM) provides Client acquisition,
Journal final assembly, direct or Workflow execution and immutable per-item results. It uses
the current `main` snapshots of Workflow, Judge, Client, SDK and Journal (versions in the root README).
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

## Direct execution, without Workflow

Workflow is optional. A subject the application invokes itself, such as a plain Claude call, is recorded as
direct execution: the row and each attempt say so, and no Workflow is created around the call.

```java
var outcome = client.invokeDirect(request, 1);     // ClaudeInvocationService; 1 = this row's first attempt
Row row = DirectExecution.assess(prepared, "COMPLETED", List.of(outcome.invocation()),
        outcome.workspaceState(), measured, factory);
```

`invokeDirect` creates an `Attempt.direct(...)` with a new acquisition identity and no Workflow coordinates, and
otherwise captures, qualifies, assembles, times out and quarantines exactly as `invoke(request, stepContext)` does.
Its provider budget is the request's. For an async Client execution, pass an `Attempt.direct(...)` to
`AsyncClientInvocationService.start(...)`: Client then records no Workflow in its correlation.

The direct state is the caller's observation: `COMPLETED` when every started attempt returned an observed
response (success, failure, a throw or a timeout), `CANCELLED` when the caller cancelled, and `INCOMPLETE` when
execution stopped without one, for example after an interruption or an unconfirmed drain. `DirectExecution.assess`
runs the Jury only for `COMPLETED` with a retained workspace; otherwise the row is `NOT_ASSESSED`. A direct row
can also be built with `Row.direct(...)`.

## Explicit untimed execution

For an opt-in untimed invocation, configure the caller-owned Client with
`defaultTimeout(null)` and its provider without an execution cutoff, then call
`AsyncClientInvocationService.startUntimed(attempt, workspace, prompt)` (or the overload
with an actual definition identity). This omits a request timeout; it does not override
a Client default or provider policy. Use the original handle's interruptible `result()`
wait and `cancel(...)` operation. Existing positive-Duration `start(...)` overloads are
unchanged. The legacy `ClaudeInvocationService.Request` remains a finite-budget path.

Author an untimed Workflow with `Workflows.define(...).withoutDeadline()`.
`WorkflowAdapter.execute(...)` uses interruptible waiting when the resulting snapshot
has no deadline and retains the finite wait for other definitions. Its `drainBudget`
must still be positive and finite. Caller interruption revokes the durable run and
requests provider termination; a racing terminal outcome is retained as observed.
An unsettled worker is quarantined with its store and partial evidence retained.
The caller owns signal handling, provider termination and saving any partial capture.

Record an untimed configured allowance as `Invocation.providerBudgetMs() == 0`.
Positive values remain finite allowances; negatives, omitted fields and nulls are
invalid. `elapsedMs` remains observed elapsed time, and unknown usage stays unknown.
The native acceptance adapter accepts this zero allowance but retains all its existing
success, identity and archive checks. Schema 4 and later readers accept this added value;
older readers may reject it. Existing archives are neither rewritten nor reinterpreted.

## Results and failure recovery

`Identity` contains run/item/variant/replicate plus a validated length-prefixed row ID. `Attempt`
adds the acquisition ID, the physical attempt number and its `ExecutionKind`: `DIRECT`, with no Workflow
coordinates, or `WORKFLOW`, with exactly one `WorkflowCoordinates` (Workflow run, logical invocation and physical
attempt IDs). The schema 4 constructor and the `workflowRunId()`-style accessors remain for Workflow attempts;
on a direct attempt the accessors throw rather than return a value. Durable values
are non-null scalars, records and concrete lists: do not use Path, Instant, Map or nullable fields.
`Measurement.UNKNOWN` carries an empty values list and reason; a measured zero or signed domain
change remains a measured value. Preserve baseline and final raw artifacts and scoped measurements
in your domain result rather than replacing them with a derived total.

Each `Row` states its `RowExecution`. `DIRECT` rows have a direct state, direct attempts only and no Workflow
proof. `WORKFLOW` rows have a Workflow state and exactly one `WorkflowProof` that contains every attempt.
`NOT_STARTED` rows have no proof and, in schema 5, no invocations. A missing proof is never read as direct
execution, and a direct row that names a Workflow, in its attempts, its proof or its acquisition provenance, is
refused. The schema 4 `Row` constructor and `workflowState()` remain for Workflow and not-started rows.

`Result(AgentRecords.CURRENT_SCHEMA_VERSION, "AGENT_CANONICAL", ...)` (schema 5) is saved once via
`FileSystemResultStore`, which writes schema 5 only. `load`, `list` and `mostRecent` validate the shape and full
associations. A result cannot be overwritten. Schema 4 files are read with their original meaning: each attempt
is a Workflow attempt with its stored coordinates, each row a Workflow or not-started row with its stored state
and proof, and the result keeps `recordingSchemaVersion` 4. Nothing is reinterpreted as direct execution, and a
schema 4 result is never written back. Explicit
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
raw historical reader; new writes use schema 5. The original Invocation constructor is retained as an
overload with an empty qualifications list.

The V3 consumer authors 30 minutes total for prepare/baseline/invoke/measure, caps the provider by
remaining time with measurement/drain reserve, and keeps domain judges' separately configured limits.
