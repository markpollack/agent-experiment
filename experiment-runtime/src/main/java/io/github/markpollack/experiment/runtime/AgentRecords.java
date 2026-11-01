package io.github.markpollack.experiment.runtime;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Non-null scalar/record/list values suitable for the released Workflow durable codec.
 */
public final class AgentRecords {

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
	 * Provider delivery is distinct from Workflow logical invocation and physical
	 * attempt.
	 */
	public record Attempt(Identity row, String invocationId, String workflowRunId, String workflowInvocationId,
			String workflowAttemptId, int attemptNumber) {
		public Attempt {
			Objects.requireNonNull(row);
			text(invocationId);
			text(workflowRunId);
			text(workflowInvocationId);
			text(workflowAttemptId);
			require(attemptNumber > 0, "Positive attempt number required");
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

	/**
	 * Retained before a Workflow commit; late records remain evidence, not accepted
	 * outputs.
	 */
	public record Invocation(Attempt attempt, String responseState, String processState, List<Integer> exitCodes,
			String captureState, List<FileRef> acquisitions, List<CanonicalRef> canonical, String assemblyState,
			List<String> providerSessions, List<Measurement> usage, String responseText, String error,
			long providerBudgetMs, long elapsedMs) {
		public Invocation {
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
			require(providerBudgetMs > 0 && elapsedMs >= 0, "Invalid timing");
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
			attempts.forEach(
					a -> require(a.row().equals(row) && a.workflowRunId().equals(runId), "Workflow proof association"));
		}
	}

	public record Row(Identity identity, List<Invocation> invocations, String workflowState, Domain domain,
			List<VerdictDocument> verdicts, String judgeState, String error, String workspaceState,
			List<WorkflowProof> workflowProof) {
		public Row {
			Objects.requireNonNull(identity);
			workflowProof = List.copyOf(workflowProof);
			invocations = List.copyOf(invocations);
			Objects.requireNonNull(domain);
			verdicts = List.copyOf(verdicts);
			require(List.of("ACTIVE", "SUCCEEDED", "FAILED", "CANCELLED", "NOT_STARTED").contains(workflowState),
					"Workflow state");
			require(List.of("RETURNED", "THREW", "NOT_ASSESSED").contains(judgeState), "Judge state");
			require(verdicts.size() == (judgeState.equals("RETURNED") ? 1 : 0), "Judge return cardinality");
			require(List.of("RETAINED", "QUARANTINED").contains(workspaceState), "Workspace ownership state");
			Objects.requireNonNull(error);
			invocations.forEach(i -> require(i.attempt().row().equals(identity), "Full row association mismatch"));
			require(invocations.stream().map(i -> i.attempt().invocationId()).distinct().count() == invocations.size(),
					"Duplicate acquisition identity");

			require(workflowProof.size() == (workflowState.equals("NOT_STARTED") ? 0 : 1), "Workflow proof missing");
			for (WorkflowProof proof : workflowProof) {
				require(proof.row().equals(identity) && proof.state().equals(workflowState),
						"Workflow proof row/state mismatch");
				invocations
					.forEach(i -> require(proof.attempts().contains(i.attempt()), "Wrong persisted Workflow attempt"));
			}
		}
	}

	public record Result(int recordingSchemaVersion, String kind, String experimentId, String experimentName,
			long createdAtEpochMs, List<Row> items, List<FileRef> runtimeArtifacts) {
		public Result {
			require(recordingSchemaVersion == 3 && kind.equals("AGENT_CANONICAL"), "Unsupported result version/kind");
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
