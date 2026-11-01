package io.github.markpollack.experiment.runtime;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import io.github.markpollack.experiment.runtime.AgentRecords.*;

public class QualificationTest {

	@TempDir
	Path temp;

	private AcquisitionAssembly.QualifiedPaths derivative(Path original, Path directory) throws Exception {
		Files.createDirectory(directory);
		Files.copy(original, directory.resolve("client-acquisition.json"));
		ObjectNode json = (ObjectNode) RuntimeJson.mapper().readTree(original.toFile());
		json.set("qualification",
				RuntimeJson.mapper().valueToTree(Map.of("original_manifest_sha256", Evidence.sha256(original))));
		Path manifest = directory.resolve("acquisition.json");
		RuntimeJson.mapper().writeValue(manifest.toFile(), json);
		Path expectation = directory.resolve("assembly-expectation.json");
		Files.writeString(expectation, "{\"kind\":\"agent-journal/assembly-expectation\",\"version\":\"0.1\"}");
		Files.writeString(directory.resolve("source.bin"), "fixture bytes");
		return new AcquisitionAssembly.QualifiedPaths(manifest, expectation);
	}

	private AcquisitionAssembly adapter(AcquisitionAssembly.Qualifier qualifier, AtomicInteger calls, boolean fail) {
		return new AcquisitionAssembly(qualifier, "fixture-qualifier", (manifest, output) -> {
			calls.incrementAndGet();
			assertThat(manifest.getParent().getFileName().toString()).endsWith("-qualification");
			if (fail)
				throw new java.io.IOException("writer failed after qualification");
			Files.createDirectory(output);
			Files.writeString(output.resolve("events.jsonl"), "{}\n");
			return "COMPLETED";
		}, "fixture-writer");
	}

	private Invocation input(String directory) throws Exception {
		var attempt = RuntimeStoreTest.attempt(Identity.of(directory, "item", "simple", 0));
		return AcquisitionTest.invocation(attempt, AcquisitionTest.manifest(temp.resolve(directory), attempt, true, 7));
	}

	private FileSystemResultStore save(Invocation invocation, String name) throws Exception {
		var id = invocation.attempt().row();
		var row = new Row(id, List.of(invocation), "NOT_STARTED", new Domain(List.of(), List.of()), List.of(),
				"NOT_ASSESSED", "", "RETAINED", List.of());
		var result = RuntimeStoreTest.result(id.runId(), List.of(row));
		var directory = temp.resolve(name);
		new FileSystemResultStore(directory).save(result);
		var store = new FileSystemResultStore(directory);
		assertThat(store.load(id.runId())).contains(result);
		assertThat(store.list()).containsExactly(result);
		assertThat(store.mostRecent()).contains(result);
		return store;
	}

	@Test
	void originalDerivativeAndClosureSurviveAllReadersAndTamperingRefuses() throws Exception {
		var input = input("original");
		var calls = new AtomicInteger();
		var output = adapter(this::derivative, calls, false).assemble(input, temp.resolve("canonical"));
		assertThat(output.assemblyState()).as(output.error()).isEqualTo("ASSEMBLED");
		assertThat(output.acquisitions()).isEqualTo(input.acquisitions());
		var q = output.qualifications().getFirst();
		assertThat(q.original()).isEqualTo(input.acquisitions().getFirst());
		assertThat(q.manifests().getFirst().sha256()).isNotEqualTo(q.original().sha256());
		assertThat(q.state()).isEqualTo("QUALIFIED");
		var store = save(output, "results");
		Evidence.verify(store.mostRecent().orElseThrow().items().getFirst());
		assertThat(calls).hasValue(1);
		Files.writeString(Path.of(q.directory()).resolve("source.bin"), "changed");
		assertThatThrownBy(() -> Evidence.verify(store.mostRecent().orElseThrow().items().getFirst()))
			.hasMessageContaining("Hash mismatch");
	}

	@Test
	void wrongOriginalHashAndAssociationRefuseBeforeQualifier() throws Exception {
		var input = input("original");
		var calls = new AtomicInteger();
		var adapter = adapter((original, directory) -> {
			calls.incrementAndGet();
			return derivative(original, directory);
		}, new AtomicInteger(), false);
		ObjectNode json = (ObjectNode) RuntimeJson.mapper()
			.readTree(Path.of(input.acquisitions().getFirst().path()).toFile());
		((ObjectNode) json.path("run").path("launch_settings").path("artifact_provenance")).put("variant", "wrong");
		RuntimeJson.mapper().writeValue(Path.of(input.acquisitions().getFirst().path()).toFile(), json);
		var refused = adapter.assemble(input, temp.resolve("hash"));
		assertThat(refused.error()).contains("Hash mismatch");
		assertThat(calls).hasValue(0);
		var freshHash = AcquisitionTest.invocation(input.attempt(),
				Evidence.reference(Path.of(input.acquisitions().getFirst().path()), "acquisition-manifest"));
		refused = adapter.assemble(freshHash, temp.resolve("association"));
		assertThat(refused.error()).contains("association mismatch");
		assertThat(calls).hasValue(0);
		save(refused, "refused-original-store");
	}

	@Test
	void swappedDerivativeAndWrongProvenanceRetainRefusalWithoutWriter() throws Exception {
		for (String mutation : List.of("attempt", "original")) {
			var input = input(mutation);
			var calls = new AtomicInteger();
			var output = adapter((original, directory) -> {
				var paths = derivative(original, directory);
				ObjectNode json = (ObjectNode) RuntimeJson.mapper().readTree(paths.manifest().toFile());
				if (mutation.equals("attempt"))
					((ObjectNode) json.path("run").path("launch_settings").path("artifact_provenance"))
						.put("workflow_attempt_id", "wrong");
				else
					((ObjectNode) json.path("qualification")).put("original_manifest_sha256", "0".repeat(64));
				RuntimeJson.mapper().writeValue(paths.manifest().toFile(), json);
				return paths;
			}, calls, false).assemble(input, temp.resolve(mutation + "-canonical"));
			assertThat(calls).hasValue(0);
			assertThat(output.assemblyState()).isEqualTo("REFUSED");
			assertThat(output.qualifications().getFirst().state()).isEqualTo("REFUSED");
			assertThat(output.qualifications().getFirst().manifests()).hasSize(1);
			assertThat(output.acquisitions()).isEqualTo(input.acquisitions());
			save(output, mutation + "-store");
		}
	}

	@Test
	void partialQualificationAndLaterAssemblyFailureRetainAvailableEvidence() throws Exception {
		var input = input("partial");
		var calls = new AtomicInteger();
		var partial = adapter((original, directory) -> {
			Files.createDirectory(directory);
			Files.writeString(directory.resolve("partial.bin"), "retained");
			throw new java.io.IOException("qualifier interrupted");
		}, calls, false).assemble(input, temp.resolve("partial-canonical"));
		assertThat(partial.qualifications().getFirst().retainedFiles()).hasSize(1);
		assertThat(partial.qualifications().getFirst().state()).isEqualTo("REFUSED");
		assertThat(calls).hasValue(0);
		save(partial, "partial-store");
		var failed = adapter(this::derivative, calls, true).assemble(input, temp.resolve("failed-writer"));
		assertThat(failed.assemblyState()).isEqualTo("REFUSED");
		assertThat(failed.qualifications().getFirst().state()).isEqualTo("QUALIFIED");
		assertThat(failed.canonical()).isEmpty();
		assertThat(calls).hasValue(1);
		save(failed, "writer-failure-store");
	}

	@Test
	void existingDirectoryIsNotClaimedAndPreviousSchemaHasExplicitRawRead() throws Exception {
		var input = input("original");
		var calls = new AtomicInteger();
		Path existing = Files.createDirectory(temp.resolve("canonical-qualification"));
		Files.writeString(existing.resolve("foreign"), "another owner");
		var refused = adapter(this::derivative, calls, false).assemble(input, temp.resolve("canonical"));
		assertThat(calls).hasValue(0);
		assertThat(refused.qualifications().getFirst().retainedFiles()).isEmpty();
		assertThat(Files.readString(existing.resolve("foreign"))).isEqualTo("another owner");
		Path old = temp.resolve("schema3.json");
		Files.writeString(old,
				"{\"recordingSchemaVersion\":3,\"kind\":\"AGENT_CANONICAL\",\"experimentId\":\"old\",\"items\":[]}");
		assertThat(FileSystemResultStore.readHistorical(old).originalJson()).isEqualTo(Files.readString(old));
	}

	public static final class Echo implements io.github.markpollack.workflow.flows.Step<Invocation, Invocation> {

		public Invocation execute(io.github.markpollack.workflow.flows.StepContext context, Invocation input) {
			return input;
		}

	}

	@Test
	void qualificationCrossesActualDurableRecordBoundary() throws Exception {
		var input = input("durable");
		var output = adapter(this::derivative, new AtomicInteger(), false).assemble(input,
				temp.resolve("durable-output"));
		var echo = new Echo();
		var graph = io.github.markpollack.workflow.flows.Workflows.define("qualified-record")
			.maxDuration(java.time.Duration.ofSeconds(10))
			.then(echo)
			.terminate(io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal.SUCCEEDED)
			.build();
		var steps = io.github.markpollack.workflow.batch.durable.StepRegistry.builder().register("echo", echo).build();
		var compatibility = new io.github.markpollack.workflow.batch.durable.ExecutionCompatibility("test",
				"qualification", Map.of());
		var execution = new WorkflowAdapter().execute(temp.resolve("durable-wf"), graph, steps, compatibility,
				input.attempt().row(), output, java.time.Duration.ofSeconds(1), () -> {
				});
		assertThat(execution.accepted()).isTrue();
		assertThat(execution.value()).isEqualTo(output);
	}

}
