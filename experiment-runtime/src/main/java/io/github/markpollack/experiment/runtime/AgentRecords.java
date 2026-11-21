package io.github.markpollack.experiment.runtime;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Non-null scalar/record/list values suitable for the released Workflow durable codec.
 */
public final class AgentRecords {

	/**
	 * Schema 5 states how every row and attempt executed: directly or under Workflow.
	 * Schema 4 recorded Workflow execution only; its records stay readable unchanged.
	 */
	public static final int CURRENT_SCHEMA_VERSION = 5;

	/** The previous schema: Workflow execution only. Read, never written. */
	public static final int SCHEMA_4 = 4;

	private AgentRecords() {
	}

	/**
	 * The row ID follows the existing length-prefixed case/candidate/replicate
	 * convention.
	 */
	public record Identity(String runId, String itemId, String variant, int replicateIndex, String rowId) {
		public Identity {
			text(runId);
			text(itemId);
			text(variant);
			require(replicateIndex >= 0, "Negative replicate");
			require(rowId.equals(key(itemId, variant, Integer.toString(replicateIndex))), "Wrong row ID");
		}

		public static Identity of(String run, String item, String variant, int replicate) {
			return new Identity(run, item, variant, replicate, key(item, variant, Integer.toString(replicate)));
		}

		public String launchKey() {
			return key(runId, rowId);
		}
	}

	/**
	 * How one attempt was executed, stated positively. A {@code DIRECT} attempt was
	 * started by the Experiment caller itself and carries no Workflow coordinates; a
	 * {@code WORKFLOW} attempt was a Workflow Step's physical attempt and carries exactly
	 * Workflow's coordinates. Absent coordinates never imply direct execution.
	 */
	public enum ExecutionKind {

		DIRECT, WORKFLOW

	}

	/** Workflow's own identity for one physical Step attempt. */
	public record WorkflowCoordinates(String runId, String invocationId, String attemptId) {
		public WorkflowCoordinates {
			text(runId);
			text(invocationId);
			text(attemptId);
		}
	}

	/**
	 * One physical provider delivery. {@code invocationId} is the Experiment's own
	 * acquisition identity, independent of how the attempt executed. {@code workflow}
	 * holds exactly one {@link WorkflowCoordinates} for {@code WORKFLOW} and none for
	 * {@code DIRECT}; the list is the durable codec's form for an optional value.
	 * {@code attemptNumber} is the 1-based physical attempt number: Workflow's number for
	 * a Workflow attempt, the caller's own count for a direct attempt.
	 */
	public record Attempt(Identity row, String invocationId, ExecutionKind execution,
			List<WorkflowCoordinates> workflow, int attemptNumber) {
		public Attempt {
			Objects.requireNonNull(row);
			text(invocationId);
			Objects.requireNonNull(execution, "Execution kind required");
			workflow = List.copyOf(workflow);
			require(workflow.size() == (execution == ExecutionKind.WORKFLOW ? 1 : 0),
					execution == ExecutionKind.WORKFLOW ? "Workflow attempt requires its Workflow coordinates"
							: "Direct attempt carries Workflow coordinates");
			require(attemptNumber > 0, "Positive attempt number required");
		}

		/** A Workflow Step attempt; the schema 4 constructor, unchanged in meaning. */
		public Attempt(Identity row, String invocationId, String workflowRunId, String workflowInvocationId,
				String workflowAttemptId, int attemptNumber) {
			this(row, invocationId, ExecutionKind.WORKFLOW,
					List.of(new WorkflowCoordinates(workflowRunId, workflowInvocationId, workflowAttemptId)),
					attemptNumber);
		}

		/** An attempt the Experiment caller started itself, without Workflow. */
		public static Attempt direct(Identity row, String invocationId, int attemptNumber) {
			return new Attempt(row, invocationId, ExecutionKind.DIRECT, List.of(), attemptNumber);
		}

		/**
		 * @return this attempt's Workflow coordinates
		 * @throws IllegalStateException for a direct attempt, which has none
		 */
		public WorkflowCoordinates workflowCoordinates() {
			if (execution != ExecutionKind.WORKFLOW)
				throw new IllegalStateException("Direct attempt has no Workflow coordinates: " + invocationId);
			return workflow.getFirst();
		}

		/**
		 * @throws IllegalStateException for a direct attempt
		 */
		public String workflowRunId() {
			return workflowCoordinates().runId();
		}

		/**
		 * @throws IllegalStateException for a direct attempt
		 */
		public String workflowInvocationId() {
			return workflowCoordinates().invocationId();
		}

		/**
		 * @throws IllegalStateException for a direct attempt
		 */
		public String workflowAttemptId() {
			return workflowCoordinates().attemptId();
		}
	}

	/** Hash subject is explicit; this is never an implicit package-integrity claim. */
	public record FileRef(String path, String sha256, String subject) {
		public FileRef {
			text(path);
			hash(sha256);
			text(subject);
		}
	}

	/**
	 * Empty values means unknown, never measured zero. Units and scope travel with the
	 * value.
	 */
	public record Measurement(String name, String unit, String state, List<Double> values, String source,
			String scope) {
		public Measurement {
			text(name);
			text(unit);
			text(source);
			text(scope);
			values = List.copyOf(values);
			require(List.of("MEASURED", "UNKNOWN").contains(state), "Unknown measurement state");
			require(values.size() == (state.equals("MEASURED") ? 1 : 0), "Measurement cardinality");
			values.forEach(v -> require(Double.isFinite(v), "Invalid measurement"));
		}

		public static Measurement unknown(String name, String unit, String reason, String scope) {
			return new Measurement(name, unit, "UNKNOWN", List.of(), reason, scope);
		}

		public static Measurement measured(String name, String unit, double value, String source, String scope) {
			return new Measurement(name, unit, "MEASURED", List.of(value), source, scope);
		}
	}

	public record CanonicalRef(FileRef events, List<FileRef> retainedFiles, String profileId, String writerVersion,
			String assemblyState, String verificationState) {
		public CanonicalRef {
			Objects.requireNonNull(events);
			retainedFiles = List.copyOf(retainedFiles);
			require(events.subject().equals("canonical-events"), "Wrong canonical hash subject");
			require(retainedFiles.contains(events), "Events missing from retained closure");
			require(retainedFiles.stream().map(FileRef::path).distinct().count() == retainedFiles.size(),
					"Duplicate retained path");
			text(profileId);
			text(writerVersion);
			text(assemblyState);
			require(verificationState.equals("NOT_SOURCE_VERIFIED"), "Source verification requires separate receipt");
		}
	}

	/** Journal-owned derivative; qualification is not source verification. */
	public record Qualification(FileRef original, String state, String directory, List<FileRef> manifests,
			List<FileRef> assemblyExpectations, List<FileRef> retainedFiles, String producer, String error) {
		public Qualification {
			Objects.requireNonNull(original);
			require(original.subject().equals("acquisition-manifest"), "Qualification original subject");
			require(List.of("QUALIFIED", "REFUSED").contains(state), "Qualification state");
			text(directory);
			text(producer);
			Objects.requireNonNull(error);
			manifests = List.copyOf(manifests);
			assemblyExpectations = List.copyOf(assemblyExpectations);
			retainedFiles = List.copyOf(retainedFiles);
			require(manifests.size() <= 1 && assemblyExpectations.size() <= 1, "Qualification reference cardinality");
			require(!state.equals("QUALIFIED") || (manifests.size() == 1 && assemblyExpectations.size() == 1),
					"Missing qualified evidence");
			require(retainedFiles.containsAll(manifests) && retainedFiles.containsAll(assemblyExpectations),
					"Qualification closure missing refs");
			require(retainedFiles.stream().map(FileRef::path).distinct().count() == retainedFiles.size(),
					"Duplicate qualification path");
			manifests.forEach(m -> require(
					m.subject().equals("qualified-acquisition-manifest") && !m.path().equals(original.path()),
					"Qualified manifest subject/path"));
			assemblyExpectations
				.forEach(e -> require(e.subject().equals("assembly-expectation"), "Expectation subject"));
		}
	}

	/**
	 * Retained before a Workflow commit; late records remain evidence, not accepted
	 * outputs. {@code providerBudgetMs} is the configured execution allowance: a positive
	 * value is finite, zero explicitly means untimed. It is not an elapsed measurement or
	 * an unknown value; {@code elapsedMs} records observed elapsed time.
	 */
	public record Invocation(Attempt attempt, String responseState, String processState, List<Integer> exitCodes,
			String captureState, List<FileRef> acquisitions, List<CanonicalRef> canonical, String assemblyState,
			List<String> providerSessions, List<Measurement> usage, String responseText, String error,
			long providerBudgetMs, long elapsedMs, List<Qualification> qualifications) {
		public Invocation(Attempt attempt, String responseState, String processState, List<Integer> exitCodes,
				String captureState, List<FileRef> acquisitions, List<CanonicalRef> canonical, String assemblyState,
				List<String> providerSessions, List<Measurement> usage, String responseText, String error,
				long providerBudgetMs, long elapsedMs) {
			this(attempt, responseState, processState, exitCodes, captureState, acquisitions, canonical, assemblyState,
					providerSessions, usage, responseText, error, providerBudgetMs, elapsedMs, List.of());
		}

		public Invocation {
			qualifications = List.copyOf(qualifications);
			require(qualifications.size() <= 1, "Qualification cardinality");
			for (Qualification q : qualifications) {
				require(acquisitions.equals(List.of(q.original())), "Qualification/original association");
				require(!assemblyState.equals("ASSEMBLED") || q.state().equals("QUALIFIED"),
						"Refused qualification assembled");
			}
			Objects.requireNonNull(attempt);
			text(responseState);
			text(processState);
			text(captureState);
			acquisitions = List.copyOf(acquisitions);
			canonical = List.copyOf(canonical);
			exitCodes = List.copyOf(exitCodes);
			providerSessions = List.copyOf(providerSessions);
			usage = List.copyOf(usage);
			Objects.requireNonNull(responseText);
			Objects.requireNonNull(error);
			require(List.of("SUCCEEDED", "FAILED", "THREW", "TIMEOUT", "UNKNOWN").contains(responseState),
					"Response state");
			require(List.of("COMPLETE", "INCOMPLETE", "MISSING", "QUARANTINED").contains(captureState),
					"Capture state");
			require(List.of("ASSEMBLED", "REFUSED", "NOT_ATTEMPTED").contains(assemblyState), "Assembly state");
			require(acquisitions.size() <= 1 && canonical.size() <= 1 && exitCodes.size() <= 1
					&& providerSessions.size() <= 1, "Reference cardinality");
			require(providerBudgetMs >= 0 && elapsedMs >= 0, "Invalid timing");
			require(!captureState.equals("COMPLETE") || acquisitions.size() == 1, "Complete capture without receipt");
			require(canonical.isEmpty() == !assemblyState.equals("ASSEMBLED"), "Assembly/reference mismatch");
			require(canonical.isEmpty() || captureState.equals("COMPLETE"), "Unsealed assembly");
			acquisitions.forEach(a -> require(a.subject().equals("acquisition-manifest"), "Wrong acquisition subject"));
		}
	}

	/** Context is constructed per item after prepare/baseline; no global workspace. */
	public record PreparedItem(Identity identity, String workspace, List<Measurement> baseline,
			List<FileRef> artifacts) {
		public PreparedItem {
			Objects.requireNonNull(identity);
			text(workspace);
			baseline = List.copyOf(baseline);
			artifacts = List.copyOf(artifacts);
		}
	}

	public record Domain(List<Measurement> measurements, List<FileRef> artifacts) {
		public Domain {
			measurements = List.copyOf(measurements);
			artifacts = List.copyOf(artifacts);
		}
	}

	/**
	 * Complete library-owned V6 text plus its own conclusion, never a lossy DTO
	 * projection.
	 */
	public record VerdictDocument(String producerJson, String sha256, String conclusion, String codec) {
		public VerdictDocument {
			text(producerJson);
			hash(sha256);
			text(conclusion);
			require(codec.equals("agent-judge-v6:0.18.0"), "Unsupported verdict codec");
			require(Evidence.sha256(producerJson.getBytes(StandardCharsets.UTF_8)).equals(sha256),
					"Verdict hash mismatch");
		}
	}

	public record WorkflowProof(Identity row, String runId, String state, String lifecycle, FileRef snapshot,
			List<Attempt> attempts) {
		public WorkflowProof {
			Objects.requireNonNull(row);
			text(runId);
			text(state);
			text(lifecycle);
			Objects.requireNonNull(snapshot);
			require(snapshot.subject().equals("workflow-snapshot"), "Wrong Workflow hash subject");
			attempts = List.copyOf(attempts);
			attempts.forEach(a -> require(a.execution() == ExecutionKind.WORKFLOW, "Direct attempt in Workflow proof"));
			attempts.forEach(
					a -> require(a.row().equals(row) && a.workflowRunId().equals(runId), "Workflow proof association"));
		}
	}

	/**
	 * How a row executed. {@code DIRECT}: the Experiment caller invoked the subject
	 * itself; there is no Workflow claim and no proof. {@code WORKFLOW}: the row ran
	 * under Workflow and carries exactly one proof. {@code NOT_STARTED}: no execution
	 * started.
	 */
	public enum RowExecution {

		DIRECT, WORKFLOW, NOT_STARTED

	}

	/** Workflow run states, as Workflow's retained snapshot reports them. */
	static final List<String> WORKFLOW_STATES = List.of("ACTIVE", "SUCCEEDED", "FAILED", "CANCELLED");

	/**
	 * Direct execution states, as the caller observed them. {@code COMPLETED}: every
	 * started attempt returned an observed response (success, failure, a throw or a
	 * timeout). {@code CANCELLED}: the caller cancelled the execution.
	 * {@code INCOMPLETE}: execution stopped without an observed response, for example
	 * after an interruption or an unconfirmed drain.
	 */
	static final List<String> DIRECT_STATES = List.of("COMPLETED", "CANCELLED", "INCOMPLETE");

	/**
	 * {@code executionState} is in {@code execution}'s vocabulary: a Workflow state, a
	 * direct state, or {@code NOT_STARTED}. {@code workflowProof} has exactly one entry
	 * for {@code WORKFLOW} and none otherwise.
	 */
	public record Row(Identity identity, List<Invocation> invocations, RowExecution execution, String executionState,
			Domain domain, List<VerdictDocument> verdicts, String judgeState, String error, String workspaceState,
			List<WorkflowProof> workflowProof) {
		public Row {
			Objects.requireNonNull(identity);
			Objects.requireNonNull(execution, "Row execution required");
			workflowProof = List.copyOf(workflowProof);
			invocations = List.copyOf(invocations);
			Objects.requireNonNull(domain);
			verdicts = List.copyOf(verdicts);
			require(List.of("RETURNED", "THREW", "NOT_ASSESSED").contains(judgeState), "Judge state");
			require(verdicts.size() == (judgeState.equals("RETURNED") ? 1 : 0), "Judge return cardinality");
			require(List.of("RETAINED", "QUARANTINED").contains(workspaceState), "Workspace ownership state");
			Objects.requireNonNull(error);
			invocations.forEach(i -> require(i.attempt().row().equals(identity), "Full row association mismatch"));
			require(invocations.stream().map(i -> i.attempt().invocationId()).distinct().count() == invocations.size(),
					"Duplicate acquisition identity");
			switch (execution) {
				case DIRECT -> {
					require(DIRECT_STATES.contains(executionState), "Direct execution state");
					require(workflowProof.isEmpty(), "Direct row carries a Workflow proof");
					invocations.forEach(i -> require(i.attempt().execution() == ExecutionKind.DIRECT,
							"Direct row carries a Workflow attempt"));
					require(!executionState.equals("COMPLETED") || (!invocations.isEmpty()
							&& invocations.stream().noneMatch(i -> i.responseState().equals("UNKNOWN"))),
							"Completed direct execution requires observed responses");
				}
				case WORKFLOW -> {
					require(WORKFLOW_STATES.contains(executionState), "Workflow state");
					require(workflowProof.size() == 1, "Workflow proof missing");
					WorkflowProof proof = workflowProof.getFirst();
					require(proof.row().equals(identity) && proof.state().equals(executionState),
							"Workflow proof row/state mismatch");
					invocations.forEach(
							i -> require(proof.attempts().contains(i.attempt()), "Wrong persisted Workflow attempt"));
				}
				case NOT_STARTED -> {
					require("NOT_STARTED".equals(executionState), "Not-started state");
					require(workflowProof.isEmpty(), "Workflow proof missing");
					// Schema 4 let a not-started row carry Workflow attempts; Result
					// refuses any invocation here for schema 5.
					invocations.forEach(i -> require(i.attempt().execution() == ExecutionKind.WORKFLOW,
							"Not-started row carries a direct attempt"));
				}
			}
		}

		/**
		 * The schema 4 constructor, unchanged in meaning: {@code NOT_STARTED} with no
		 * proof, otherwise a Workflow state with exactly one proof.
		 */
		public Row(Identity identity, List<Invocation> invocations, String workflowState, Domain domain,
				List<VerdictDocument> verdicts, String judgeState, String error, String workspaceState,
				List<WorkflowProof> workflowProof) {
			this(identity, invocations, legacyExecution(workflowState), workflowState, domain, verdicts, judgeState,
					error, workspaceState, workflowProof);
		}

		/** A row the Experiment caller executed directly, without Workflow. */
		public static Row direct(Identity identity, List<Invocation> invocations, String directState, Domain domain,
				List<VerdictDocument> verdicts, String judgeState, String error, String workspaceState) {
			return new Row(identity, invocations, RowExecution.DIRECT, directState, domain, verdicts, judgeState, error,
					workspaceState, List.of());
		}

		/**
		 * @return the Workflow state, or {@code NOT_STARTED}
		 * @throws IllegalStateException for a direct row, which has no Workflow state
		 */
		public String workflowState() {
			if (execution == RowExecution.DIRECT)
				throw new IllegalStateException("Direct row has no Workflow state: " + identity.rowId());
			return executionState;
		}

		private static RowExecution legacyExecution(String workflowState) {
			return "NOT_STARTED".equals(workflowState) ? RowExecution.NOT_STARTED : RowExecution.WORKFLOW;
		}
	}

	public record Result(int recordingSchemaVersion, String kind, String experimentId, String experimentName,
			long createdAtEpochMs, List<Row> items, List<FileRef> runtimeArtifacts) {
		public Result {
			require((recordingSchemaVersion == CURRENT_SCHEMA_VERSION || recordingSchemaVersion == SCHEMA_4)
					&& "AGENT_CANONICAL".equals(kind), "Unsupported result version/kind");
			text(experimentId);
			text(experimentName);
			require(createdAtEpochMs >= 0, "Invalid timestamp");
			items = List.copyOf(items);
			runtimeArtifacts = List.copyOf(runtimeArtifacts);
			require(!items.isEmpty(), "Empty declared result");
			items.forEach(r -> require(r.identity().runId().equals(experimentId), "Wrong result run"));
			require(items.stream().map(r -> r.identity().rowId()).distinct().count() == items.size(), "Duplicate row");
			long count = items.stream().mapToLong(r -> r.invocations().size()).sum();
			require(items.stream()
				.flatMap(r -> r.invocations().stream())
				.map(i -> i.attempt().invocationId())
				.distinct()
				.count() == count, "Reused acquisition across rows");
			items.forEach(r -> r.verdicts().forEach(JudgeRecording::validate));
			if (recordingSchemaVersion == SCHEMA_4)
				items.forEach(
						r -> require(r.execution() != RowExecution.DIRECT, "Schema 4 cannot record direct execution"));
			else
				items.forEach(r -> require(r.execution() != RowExecution.NOT_STARTED || r.invocations().isEmpty(),
						"Not-started row carries invocations; record DIRECT or WORKFLOW execution"));
		}
	}

	static String key(String... parts) {
		StringBuilder value = new StringBuilder();
		for (String part : parts)
			value.append(part.getBytes(StandardCharsets.UTF_8).length).append(':').append(part);
		return value.toString();
	}

	static void text(String v) {
		require(v != null && !v.isBlank(), "Required text missing");
	}

	static void hash(String v) {
		require(v != null && v.matches("[a-f0-9]{64}"), "Invalid SHA-256");
	}

	static void require(boolean ok, String reason) {
		if (!ok)
			throw new IllegalArgumentException(reason);
	}

}
