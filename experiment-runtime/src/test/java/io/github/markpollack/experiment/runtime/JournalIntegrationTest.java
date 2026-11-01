package io.github.markpollack.experiment.runtime;

import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import io.github.markpollack.experiment.runtime.AgentRecords.*;
import io.github.markpollack.journal.tck.ocsf.PackageReader;

/** Sanitized replay through the real installed writer/reader, never a fresh V3 claim. */
class JournalIntegrationTest {

	@TempDir
	Path temp;

	@Test
	void realWriterAndPackagedReaderPreserveFailedProcessAndCanonicalEvidence() throws Exception {
		Path fixture = Path.of("src/test/resources/journal-replay");
		var mapper = RuntimeJson.mapper();
		ObjectNode run = (ObjectNode) mapper.readTree(fixture.resolve("spec.json").toFile());
		var identity = Identity.of("experiment-run", "item", "simple", 0);
		var attempt = new Attempt(identity, run.path("run_uid").asText(), "wf", "logical", "physical", 1);
		((ObjectNode) run.path("launch_settings")).set("artifact_provenance",
				mapper.valueToTree(AcquisitionAssembly.provenance(attempt)));
		var expected = mapper.readTree(fixture.resolve("native/expected-sources.json").toFile());
		ArrayNode sources = mapper.createArrayNode();
		int index = 0;
		for (var input : run.path("assembly").path("inputs")) {
			String name = input.path("artifact").asText();
			Files.copy(fixture.resolve("native").resolve(name), temp.resolve(name));
			ObjectNode source = ((ObjectNode) expected.path("sources").get(index)).deepCopy();
			source.setAll((ObjectNode) input);
			source.put("path", name)
				.put("route", index++ == 0 ? "stdout" : "native_session_file")
				.put("framing", "lines")
				.put("state", "acquired")
				.put("end", "EOF");
			sources.add(source);
		}
		ObjectNode manifest = mapper.createObjectNode()
			.put("kind", "agent-journal/acquired-execution")
			.put("version", "0.1")
			.put("profile", "claude-code-2.1.304-stdout-session-file")
			.put("profile_id", "claude-code/2.1.304/stdout+session-file")
			.put("acquisition_uid", attempt.invocationId())
			.put("complete", true);
		manifest.set("run", run);
		manifest.set("sources", sources);
		manifest.set("process", mapper.valueToTree(Map.of("status", "exited", "exit_code", 7)));
		Path acquired = temp.resolve("acquisition.json");
		mapper.writeValue(acquired.toFile(), manifest);
		var invocation = AcquisitionTest.invocation(attempt, Evidence.reference(acquired, "acquisition-manifest"));
		var assembled = AcquisitionAssembly.pinnedJournal().assemble(invocation, temp.resolve("canonical"));
		assertThat(assembled.assemblyState()).as(assembled.error()).isEqualTo("ASSEMBLED");
		assertThat(assembled.responseState()).isEqualTo("FAILED");
		assertThat(assembled.exitCodes()).containsExactly(7);
		var canonical = assembled.canonical().getFirst();
		Evidence.verify(canonical);
		assertThat(PackageReader.open(Path.of(canonical.events().path())).refused()).isFalse();
		assertThat(PackageReader.open(Path.of(canonical.events().path())).events("tool_call", 1)).hasSize(4);
		var row = new Row(identity, List.of(assembled), "NOT_STARTED", new Domain(List.of(), List.of()), List.of(),
				"NOT_ASSESSED", "", "RETAINED", List.of());
		var store = new FileSystemResultStore(temp.resolve("results"));
		store.save(RuntimeStoreTest.result(identity.runId(), List.of(row)));
		var reopened = new FileSystemResultStore(temp.resolve("results"));
		assertThat(reopened.load(identity.runId()).orElseThrow().items()).containsExactly(row);
		Evidence.verify(reopened.mostRecent().orElseThrow().items().getFirst());
		// Export the real adapter output for a separate-process packaged-reader check.
		Path exported = Path.of("target/example/journal-replay");
		try (var paths = Files.walk(temp)) {
			for (Path source : paths.filter(Files::isRegularFile).toList()) {
				Path destination = exported.resolve(temp.relativize(source));
				Files.createDirectories(destination.getParent());
				Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		Files.copy(fixture.resolve("native/assembly-expectation.json"), exported.resolve("assembly-expectation.json"),
				StandardCopyOption.REPLACE_EXISTING);
		Files.copy(fixture.resolve("native/expected-sources.json"), exported.resolve("expected-sources.json"),
				StandardCopyOption.REPLACE_EXISTING);
		Files.writeString(Path.of(canonical.events().path()), "tampered");
		assertThatThrownBy(() -> Evidence.verify(reopened.load(identity.runId()).orElseThrow().items().getFirst()))
			.hasMessageContaining("Hash mismatch");
	}

}
