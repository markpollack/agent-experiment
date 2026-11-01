package io.github.markpollack.experiment.runtime;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.markpollack.experiment.runtime.AgentRecords.*;

/**
 * The single Experiment assembly caller. Inputs must be sealed and associated before
 * entry.
 */
public final class AcquisitionAssembly {

	@FunctionalInterface
	public interface Assembler {

		String assemble(Path sealedManifest, Path newOutput) throws Exception;

	}

	private final Assembler assembler;

	private final String writerVersion;

	public AcquisitionAssembly(Assembler assembler, String writerVersion) {
		this.assembler = Objects.requireNonNull(assembler);
		AgentRecords.text(writerVersion);
		this.writerVersion = writerVersion;
	}

	public static Map<String, String> provenance(Attempt attempt) {
		var r = attempt.row();
		return Map.of("experiment_run_id", r.runId(), "item_id", r.itemId(), "variant", r.variant(), "replicate_index",
				Integer.toString(r.replicateIndex()), "row_id", r.rowId(), "experiment_invocation_id",
				attempt.invocationId(), "workflow_run_id", attempt.workflowRunId(), "workflow_invocation_id",
				attempt.workflowInvocationId(), "workflow_attempt_id", attempt.workflowAttemptId(),
				"workflow_attempt_number", Integer.toString(attempt.attemptNumber()));
	}

	public static JsonNode validateManifest(FileRef acquisition, Attempt attempt, boolean requireComplete)
			throws IOException {
		if (!acquisition.subject().equals("acquisition-manifest"))
			throw new IOException("Wrong hash subject");
		Evidence.verify(acquisition);
		JsonNode tree = RuntimeJson.mapper().readTree(Path.of(acquisition.path()).toFile());
		if (!tree.path("kind").asText().equals("agent-journal/acquired-execution")
				|| !tree.path("version").asText().equals("0.1"))
			throw new IOException("Unsupported acquisition version");
		if (!tree.path("complete").isBoolean())
			throw new IOException("Missing acquisition completeness");
		if (requireComplete && !tree.path("complete").booleanValue())
			throw new IOException("Unsealed/incomplete acquisition");
		if (!tree.path("acquisition_uid").asText().equals(attempt.invocationId())
				|| !tree.path("run").path("run_uid").asText().equals(attempt.invocationId()))
			throw new IOException("Acquisition/invocation mismatch");
		JsonNode provenance = tree.path("run").path("launch_settings").path("artifact_provenance");
		for (var expected : provenance(attempt).entrySet()) {
			if (!provenance.path(expected.getKey()).isTextual()
					|| !provenance.path(expected.getKey()).textValue().equals(expected.getValue()))
				throw new IOException("Acquisition association mismatch: " + expected.getKey());
		}
		return tree;
	}

	public Invocation assemble(Invocation invocation, Path freshOutput) {
		if (!invocation.captureState().equals("COMPLETE"))
			return invocation;
		if (!invocation.assemblyState().equals("NOT_ATTEMPTED"))
			throw new IllegalArgumentException("Assembly already selected");
		try {
			JsonNode manifest = validateManifest(invocation.acquisitions().getFirst(), invocation.attempt(), true);
			if (Files.exists(freshOutput))
				throw new IOException("Refusing existing canonical output");
			String state = assembler.assemble(Path.of(invocation.acquisitions().getFirst().path()), freshOutput);
			List<FileRef> files = new ArrayList<>();
			Path events = freshOutput.resolve("events.jsonl").toAbsolutePath().normalize();
			try (var paths = Files.walk(freshOutput)) {
				for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
					if (Files.isSymbolicLink(file))
						throw new IOException("Linked canonical file escapes retained closure");
					files.add(Evidence.reference(file, file.toAbsolutePath().normalize().equals(events)
							? "canonical-events" : "canonical-linked-file"));
				}
			}
			FileRef eventRef = files.stream()
				.filter(f -> f.subject().equals("canonical-events"))
				.findFirst()
				.orElseThrow(() -> new IOException("Assembly produced no events"));
			CanonicalRef reference = new CanonicalRef(eventRef, files, manifest.path("profile_id").asText(),
					writerVersion, state, "NOT_SOURCE_VERIFIED");
			return copy(invocation, List.of(reference), "ASSEMBLED", invocation.error());
		}
		catch (Exception failure) {
			return copy(invocation, List.of(), "REFUSED",
					failure.getClass().getName() + ": " + String.valueOf(failure.getMessage()));
		}
	}

	private static Invocation copy(Invocation i, List<CanonicalRef> canonical, String state, String error) {
		return new Invocation(i.attempt(), i.responseState(), i.processState(), i.exitCodes(), i.captureState(),
				i.acquisitions(), canonical, state, i.providerSessions(), i.usage(), i.responseText(), error,
				i.providerBudgetMs(), i.elapsedMs());
	}

}
