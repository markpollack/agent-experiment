package io.github.markpollack.experiment.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.markpollack.agents.client.*;
import io.github.markpollack.experiment.runtime.AgentRecords.*;

/**
 * Admits an already completed successful one-shot native Client archive into existing
 * records.
 */
public final class NativeClientInvocation {

	private NativeClientInvocation() {
	}

	/**
	 * Reads the original elected response and actual producer artifacts without starting,
	 * waiting for, closing or replaying provider work. Non-success/incomplete admission
	 * is refused; the caller retains the original handle, error, sources and
	 * launched-attempt count. This method does not implement generalized failure
	 * persistence. Independent archive verification remains a separate acceptance
	 * receipt. Detached validation binds SUCCESS and identities, not response text absent
	 * from the Client decision codec.
	 * @param started original configured async execution and exact Attempt
	 * @param termination actual matching observed-resource settlement
	 * @param originalManifest original Client acquisition manifest
	 * @param qualificationDirectory actual Journal qualifier output
	 * @param preparedEvents exact events file selected by the actual terminal ACK
	 * @param providerBudgetMs configured task allowance
	 * @param elapsedMs observed elapsed time
	 * @return successful Invocation with unchanged source identities and full retained
	 * closure
	 * @throws IOException if any source, association or admission check fails
	 * @throws InterruptedException if Client response access is interrupted
	 * @throws TimeoutException if the already elected response is not yet available
	 */
	public static Invocation accept(AsyncClientInvocationService.StartedExecution started,
			ExecutionTermination termination, Path originalManifest, Path qualificationDirectory, Path preparedEvents,
			long providerBudgetMs, long elapsedMs) throws IOException, InterruptedException, TimeoutException {
		Objects.requireNonNull(started);
		Objects.requireNonNull(termination);
		var execution = started.execution();
		var decision = started.decision().orElseThrow(() -> new IOException("No elected decision"));
		NativeRecordingEvidence.require(decision.outcome() instanceof ExecutionOutcome.Success,
				"Only elected SUCCESS may be admitted");
		NativeRecordingEvidence.require(
				termination.cleanupConfirmed() && termination.settledAt().isPresent()
						&& termination.providerTurnDispatched() && termination.unconfirmedResources().isEmpty()
						&& execution.executionId().equals(termination.executionId())
						&& execution.sessionId().equals(termination.sessionId()),
				"Unconfirmed or unrelated resource settlement");
		var recording = execution.recording();
		NativeRecordingEvidence.require(
				recording.executionId().equals(execution.executionId())
						&& recording.decisionId().orElse("").equals(decision.decisionId())
						&& recording.evidence().executionId().equals(execution.executionId())
						&& recording.evidence().clientSessionId().equals(execution.sessionId())
						&& recording.evidence().provider().equals(decision.provider())
						&& recording.evidence().acquisition() == CaptureAcquisition.COMPLETED
						&& recording.delivery() instanceof TerminalDelivery.Completed,
				"Incomplete or unrelated terminal recording");
		var nativeRecording = recording.nativeRecording()
			.orElseThrow(() -> new IOException("Client reported no native recording outcome"));
		NativeRecordingEvidence.require(nativeRecording.outcome() == NativeRecording.Outcome.ATTACHED,
				"Journal did not qualify the native sources: " + nativeRecording.outcome() + " "
						+ nativeRecording.detail());
		var completed = (TerminalDelivery.Completed) recording.delivery();
		var response = execution.result(Duration.ZERO);
		NativeRecordingEvidence.require(response.isSuccessful(), "Elected response is not successful");
		var original = Evidence.reference(originalManifest, "acquisition-manifest");
		var qualification = QualificationEvidence.collect(original, qualificationDirectory, "QUALIFIED",
				"journal-capture-tck:2.0.0-SNAPSHOT", "");
		NativeRecordingEvidence.require(qualification.state().equals("QUALIFIED"),
				"Journal qualification output is incomplete or unsafe: " + qualificationDirectory);
		JsonNode manifest = RuntimeJson.mapper().readTree(originalManifest.toFile());
		var retained = new ArrayList<FileRef>();
		Path events = preparedEvents.toAbsolutePath().normalize();
		try (var files = Files.walk(events.getParent())) {
			for (Path p : files.filter(Files::isRegularFile).sorted().toList())
				retained.add(Evidence.reference(p, p.equals(events) ? "canonical-events" : "canonical-linked-file"));
		}
		var canonical = new CanonicalRef(Evidence.reference(events, "canonical-events"), retained,
				NativeRecordingEvidence.requiredText(manifest, "profile_id"), "journal-ocsf-writer:2.0.0-SNAPSHOT",
				"COMPLETED", "NOT_SOURCE_VERIFIED");
		JsonNode exit = manifest.path("process").path("exit_code");
		NativeRecordingEvidence.require(exit.isNull() || exit.isIntegralNumber(),
				"Unsupported process exit projection");
		var invocation = new Invocation(started.attempt(), "SUCCEEDED", NativeRecordingEvidence.processState(manifest),
				exit.isNull() ? List.of() : List.of(exit.intValue()), "COMPLETE", List.of(original), List.of(canonical),
				"ASSEMBLED", List.of(NativeRecordingEvidence.requiredText(manifest.path("run"), "session_uid")),
				List.of(Measurement.unknown("cost", "USD", "No qualified scoped accounting selected",
						started.attempt().invocationId()),
						Measurement.unknown("tokens", "count", "No qualified scoped accounting selected",
								started.attempt().invocationId())),
				response.getResult(), "", providerBudgetMs, elapsedMs, List.of(qualification));
		var facts = NativeRecordingEvidence.verify(invocation);
		JsonNode saved = facts.decision();
		NativeRecordingEvidence.equal(facts.marker().path("ack"), "bindingId", completed.bindingId());
		NativeRecordingEvidence.equal(saved, "decisionId", decision.decisionId());
		NativeRecordingEvidence.equal(saved, "executionId", decision.executionId());
		NativeRecordingEvidence.equal(saved, "sessionId", decision.sessionId());
		NativeRecordingEvidence.equal(saved, "connectionId",
				decision.connectionId().orElseThrow(() -> new IOException("No native connection")));
		NativeRecordingEvidence.require(Instant.parse(saved.path("acceptedAt").asText()).equals(decision.acceptedAt()),
				"Decision acceptance instant changed");
		JsonNode definition = saved.path("correlation").path("experimentId");
		NativeRecordingEvidence.require(decision.correlation()
			.experimentId()
			.map(s -> definition.isTextual() && s.equals(definition.textValue()))
			.orElseGet(definition::isNull), "Experiment definition mismatch");
		var provider = saved.path("provider");
		NativeRecordingEvidence.equal(provider, "kind", decision.provider().kind().name());
		NativeRecordingEvidence.require(decision.provider()
			.nativeReason()
			.map(s -> s.equals(provider.path("nativeReason").textValue()))
			.orElseGet(() -> provider.path("nativeReason").isNull()), "Provider reason changed");
		NativeRecordingEvidence.require(decision.provider().observedExitCode().isPresent()
				? provider.path("observedExitCode").isIntegralNumber() && provider.path("observedExitCode")
					.intValue() == decision.provider().observedExitCode().getAsInt()
				: provider.path("observedExitCode").isNull(), "Provider exit changed");
		return invocation;
	}

}
