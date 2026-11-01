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

	@FunctionalInterface
	public interface Qualifier {

		QualifiedPaths qualify(Path sealedManifest, Path newDirectory) throws Exception;

	}

	public record QualifiedPaths(Path manifest, Path assemblyExpectation) {
	}

	private final Qualifier qualifier;

	private final String qualifierVersion;

	private final Assembler assembler;

	private final String writerVersion;

	private final Set<String> selected = java.util.concurrent.ConcurrentHashMap.newKeySet();

	public AcquisitionAssembly(Assembler assembler, String writerVersion) {
		this(null, "already-bound-input", assembler, writerVersion);
	}

	public AcquisitionAssembly(Qualifier qualifier, String qualifierVersion, Assembler assembler,
			String writerVersion) {
		this.qualifier = qualifier;
		AgentRecords.text(qualifierVersion);
		this.qualifierVersion = qualifierVersion;
		this.assembler = Objects.requireNonNull(assembler);
		AgentRecords.text(writerVersion);
		this.writerVersion = writerVersion;
	}

	/** Fresh source route: Journal qualifies its own source bindings before assembly. */
	public static AcquisitionAssembly journal() {
		return new AcquisitionAssembly((original, directory) -> {
			var q = io.github.markpollack.journal.tck.ocsf.inventory.ClaudeAcquisition.qualify(original, directory);
			return new QualifiedPaths(q.manifest(), q.assemblyExpectation());
		}, "journal-capture-tck:2.0.0-fresh-v3-qualification-SNAPSHOT", (manifest,
				output) -> io.github.markpollack.journal.ocsf.writer.FinalAssembly.assemble(manifest, output).name(),
				"journal-ocsf-writer:2.0.0-fresh-v3-qualification-SNAPSHOT");
	}

	/**
	 * Explicit already-bound route; never selected as a qualification failure fallback.
	 */
	public static AcquisitionAssembly pinnedJournal() {
		return new AcquisitionAssembly((manifest,
				output) -> io.github.markpollack.journal.ocsf.writer.FinalAssembly.assemble(manifest, output).name(),
				"journal-ocsf-writer:2.0.0-fresh-v3-qualification-SNAPSHOT");
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
		if (!List.of("acquisition-manifest", "qualified-acquisition-manifest").contains(acquisition.subject()))
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
		List<Qualification> qualifications = invocation.qualifications();
		Path qualifiedDirectory = freshOutput.toAbsolutePath()
			.normalize()
			.resolveSibling(freshOutput.getFileName() + "-qualification");
		boolean qualificationStarted = false;
		boolean qualificationAccepted = false;
		try {
			JsonNode manifest = validateManifest(invocation.acquisitions().getFirst(), invocation.attempt(), true);
			if (!selected
				.add(invocation.attempt().invocationId() + ":" + invocation.acquisitions().getFirst().sha256()))
				throw new IOException("Acquisition already selected for assembly");
			if (Files.exists(freshOutput))
				throw new IOException("Refusing existing canonical output");
			Path assemblyInput = Path.of(invocation.acquisitions().getFirst().path());
			if (qualifier != null) {
				if (!qualifications.isEmpty())
					throw new IOException("Qualification already selected");
				if (Files.exists(qualifiedDirectory))
					throw new IOException("Refusing existing qualification directory");
				qualificationStarted = true;
				QualifiedPaths paths = qualifier.qualify(assemblyInput, qualifiedDirectory);
				if (!paths.manifest()
					.toAbsolutePath()
					.normalize()
					.equals(qualifiedDirectory.resolve("acquisition.json"))
						|| !paths.assemblyExpectation()
							.toAbsolutePath()
							.normalize()
							.equals(qualifiedDirectory.resolve("assembly-expectation.json")))
					throw new IOException("Qualifier returned paths outside its contract");
				Qualification q = QualificationEvidence.collect(invocation.acquisitions().getFirst(),
						qualifiedDirectory, "QUALIFIED", qualifierVersion, "");
				if (!q.state().equals("QUALIFIED"))
					throw new IOException("Incomplete qualification: " + q.error());
				QualificationEvidence.verify(q, invocation.attempt());
				qualifications = List.of(q);
				qualificationAccepted = true;
				assemblyInput = paths.manifest();
				manifest = validateManifest(q.manifests().getFirst(), invocation.attempt(), true);
			}
			String state = assembler.assemble(assemblyInput, freshOutput);
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
			return copy(invocation, List.of(reference), "ASSEMBLED", invocation.error(), qualifications);
		}
		catch (Exception failure) {
			String reason = failure.getClass().getName() + ": " + String.valueOf(failure.getMessage());
			if (qualifier != null && !qualificationAccepted) {
				qualifications = List.of(qualificationStarted
						? QualificationEvidence.collect(invocation.acquisitions().getFirst(), qualifiedDirectory,
								"REFUSED", qualifierVersion, reason)
						: new Qualification(invocation.acquisitions().getFirst(), "REFUSED",
								qualifiedDirectory.toString(), List.of(), List.of(), List.of(), qualifierVersion,
								reason));
			}
			return copy(invocation, List.of(), "REFUSED", reason, qualifications);
		}
	}

	private static Invocation copy(Invocation i, List<CanonicalRef> canonical, String state, String error,
			List<Qualification> qualifications) {
		return new Invocation(i.attempt(), i.responseState(), i.processState(), i.exitCodes(), i.captureState(),
				i.acquisitions(), canonical, state, i.providerSessions(), i.usage(), i.responseText(), error,
				i.providerBudgetMs(), i.elapsedMs(), qualifications);
	}

}
