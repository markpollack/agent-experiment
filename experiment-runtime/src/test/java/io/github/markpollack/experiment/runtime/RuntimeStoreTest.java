package io.github.markpollack.experiment.runtime;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import io.github.markpollack.experiment.runtime.AgentRecords.*;
import io.github.markpollack.judge.judgment.Judgment;
import io.github.markpollack.judge.verdict.Verdict;

class RuntimeStoreTest {

	@TempDir
	Path temp;

	static Identity identity(String run, int replicate) {
		return Identity.of(run, "item", "simple", replicate);
	}

	static Attempt attempt(Identity id) {
		return new Attempt(id, "inv-" + id.runId() + "-" + id.replicateIndex(), "wf", "logical", "physical", 1);
	}

	static Invocation missing(Identity id) {
		return new Invocation(attempt(id), "SUCCEEDED", "UNKNOWN", List.of(), "MISSING", List.of(), List.of(),
				"NOT_ATTEMPTED", List.of(), List.of(Measurement.unknown("cost", "USD", "not reported", "invocation")),
				"answer", "", 1000, 5);
	}

	static Row row(Identity id) {
		return new Row(id, List.of(missing(id)), "NOT_STARTED", new Domain(List.of(), List.of()), List.of(),
				"NOT_ASSESSED", "", "RETAINED", List.of());
	}

	static Result result(String run, List<Row> rows) {
		return new Result(3, "AGENT_CANONICAL", run, "experiment", 100, rows, List.of());
	}

	@Test
	void realStoreAllReadersRetainUnknownAndEqualInputReplicates() throws Exception {
		var store = new FileSystemResultStore(temp);
		var run = result("run-1", List.of(row(identity("run-1", 0)), row(identity("run-1", 1))));
		store.save(run);
		store.save(result("run-2", List.of(row(identity("run-2", 0)))));
		var reopened = new FileSystemResultStore(temp);
		assertThat(reopened.load("run-1")).contains(run);
		assertThat(reopened.list()).hasSize(2);
		assertThat(reopened.mostRecent()).isPresent();
		assertThat(reopened.load("run-1")
			.orElseThrow()
			.items()
			.getFirst()
			.invocations()
			.getFirst()
			.usage()
			.getFirst()
			.values()).isEmpty();
		assertThatThrownBy(() -> store.save(run)).isInstanceOf(FileAlreadyExistsException.class);
	}

	@Test
	void wrongRunDuplicateRowsAndUnknownVersionsRefuse() throws Exception {
		assertThatThrownBy(() -> result("one", List.of(row(identity("two", 0)))))
			.hasMessageContaining("Wrong result run");
		var row = row(identity("one", 0));
		assertThatThrownBy(() -> result("one", List.of(row, row))).hasMessageContaining("Duplicate row");
		var store = new FileSystemResultStore(temp);
		store.save(result("one", List.of(row)));
		Path file;
		try (var paths = Files.walk(temp)) {
			file = paths.filter(p -> p.getFileName().toString().equals("run.json")).findFirst().orElseThrow();
		}
		var json = RuntimeJson.mapper().readTree(file.toFile());
		((com.fasterxml.jackson.databind.node.ObjectNode) json).put("recordingSchemaVersion", 99);
		RuntimeJson.mapper().writeValue(file.toFile(), json);
		assertThatThrownBy(() -> store.load("one")).hasMessageContaining("Unsupported current result");
		assertThatThrownBy(store::list).hasMessageContaining("Unsupported current result");
		assertThatThrownBy(store::mostRecent).hasMessageContaining("Unsupported current result");
	}

	@Test
	void allReadersRejectMissingMandatoryIdentity() throws Exception {
		var store = new FileSystemResultStore(temp);
		store.save(result("one", List.of(row(identity("one", 0)))));
		Path file;
		try (var paths = Files.walk(temp)) {
			file = paths.filter(p -> p.getFileName().toString().equals("run.json")).findFirst().orElseThrow();
		}
		var json = RuntimeJson.mapper().readTree(file.toFile());
		((com.fasterxml.jackson.databind.node.ObjectNode) json.at("/items/0/identity")).remove("runId");
		RuntimeJson.mapper().writeValue(file.toFile(), json);
		assertThatThrownBy(() -> store.load("one")).hasMessageContaining("runId");
		assertThatThrownBy(store::list).hasMessageContaining("runId");
	}

	@Test
	void completeV6AndJudgeFailureRetainIndependentFacts() throws Exception {
		var id = identity("run", 0);
		var prepared = new PreparedItem(id, temp.toString(),
				List.of(Measurement.measured("baseline", "percent", 0, "report", "item")), List.of());
		var domain = new Domain(
				List.of(Measurement.unknown("coverage", "percent", "build failed", "item"),
						Measurement.measured("coverageDelta", "percentage-point", -5, "fixture regression", "item")),
				List.of());
		var failedDomain = JudgeRecording.assess(prepared, List.of(missing(id)), "NOT_STARTED", domain,
				(p, d) -> () -> Verdict.single("build", Judgment.fail("actual failing domain outcome")), List.of());
		var thrown = JudgeRecording.assess(prepared, List.of(missing(id)), "NOT_STARTED", domain, (p, d) -> {
			throw new IllegalStateException("judge failed after capture");
		}, List.of());
		assertThat(failedDomain.verdicts().getFirst().conclusion()).isEqualTo("FAIL");
		assertThat(thrown.judgeState()).isEqualTo("THREW");
		assertThat(thrown.invocations()).containsExactly(missing(id));
		var store = new FileSystemResultStore(temp.resolve("results"));
		store.save(result("run", List.of(failedDomain)));
		var read = new FileSystemResultStore(temp.resolve("results")).load("run").orElseThrow();
		assertThat(read.items().getFirst()).isEqualTo(failedDomain);
		var doc = read.items().getFirst().verdicts().getFirst();
		assertThat(RuntimeJson.mapper().readTree(doc.producerJson()))
			.isEqualTo(RuntimeJson.mapper().valueToTree(JudgeRecording.read(doc)));
		assertThat(prepared.baseline().getFirst().values()).containsExactly(0.0);
		assertThat(domain.measurements().getFirst().values()).isEmpty();
	}

	@Test
	void namedHashSubjectsAndClosureDoNotClaimSourceVerification() throws Exception {
		Path events = temp.resolve("events.jsonl");
		Files.writeString(events, "{}\n");
		Path linked = temp.resolve("child.jsonl");
		Files.writeString(linked, "{}\n");
		var e = Evidence.reference(events, "canonical-events");
		var c = new CanonicalRef(e, List.of(e, Evidence.reference(linked, "canonical-linked-file")), "profile",
				"writer", "FINALIZED", "NOT_SOURCE_VERIFIED");
		Evidence.verify(c);
		Files.writeString(linked, "changed");
		assertThatThrownBy(() -> Evidence.verify(c)).hasMessageContaining("Hash mismatch");
		Files.delete(linked);
		assertThatThrownBy(() -> Evidence.verify(c)).isInstanceOf(java.io.IOException.class);
	}

	@Test
	void actualHistoricalFixtureNeedsNoRetiredClasses() throws Exception {
		Path fixture = Path.of("src/test/resources/historical/experiment-result-0.5-judge-0.13.json");
		var historical = FileSystemResultStore.readHistorical(fixture);
		assertThat(historical.originalJson()).isEqualTo(Files.readString(fixture));
		assertThat(historical.sha256()).isEqualTo(Evidence.sha256(fixture));
		assertThat(historical.state()).isEqualTo("LEGACY_UNVERIFIED");
		assertThatThrownBy(() -> Class.forName("io.github.markpollack.journal.claude.PhaseCapture"))
			.isInstanceOf(ClassNotFoundException.class);
	}

	@Test
	void historicalBytesRemainExplicitlyUnverified() throws Exception {
		String historical = "{\"experimentId\":\"old\",\"recordingSchemaVersion\":2,\"items\":[{\"executionDetail\":{\"phases\":[{\"native\":\"retained\"}]},\"interpretationSnapshot\":{\"document\":{\"old\":true}}}]}";
		Path file = temp.resolve("old.json");
		Files.writeString(file, historical);
		var read = FileSystemResultStore.readHistorical(file);
		assertThat(read.originalJson()).isEqualTo(historical);
		assertThat(read.state()).isEqualTo("LEGACY_UNVERIFIED");
	}

}
