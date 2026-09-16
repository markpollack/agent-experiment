package io.github.markpollack.experiment.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.markpollack.experiment.store.InterpretationReExport.Report;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The re-export adds a reading beside a stored verdict. These pin the three properties
 * that make it safe to run over an archive that cannot be regenerated.
 */
class InterpretationReExportTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	@TempDir
	Path dir;

	@Test
	void addsAnInterpretationAndLeavesTheVerdictExactlyAsItWas() throws Exception {
		Path file = write("run.json", """
				{
				  "runId" : "r1",
				  "items" : [ {
				    "itemId" : "a",
				    "passed" : false,
				    "verdict" : {
				      "aggregated" : { "status" : "error", "reasoning" : "boom" },
				      "individual" : [ ],
				      "weights" : { "judge" : 0.5 }
				    }
				  } ]
				}
				""");
		JsonNode verdictBefore = JSON.readTree(Files.readString(file)).path("items").get(0).get("verdict");

		Report report = reExport(reading("NOT_ASSESSED")).run(List.of(file), false);

		assertThat(report.added()).isEqualTo(1);
		assertThat(report.filesFailed()).isZero();
		JsonNode item = JSON.readTree(Files.readString(file)).path("items").get(0);
		assertThat(item.path("interpretation").path("reading").asText()).isEqualTo("NOT_ASSESSED");
		assertThat(item.get("verdict")).isEqualTo(verdictBefore);
		// the rest of the item is still there, and untouched
		assertThat(item.path("passed").asBoolean()).isFalse();
	}

	@Test
	void runningItTwiceChangesNothingTheSecondTime() throws Exception {
		Path file = write("run.json", oneItemWithVerdict());
		reExport(reading("REJECTED")).run(List.of(file), false);
		String afterFirst = Files.readString(file);

		Report second = reExport(reading("REJECTED")).run(List.of(file), false);

		assertThat(second.added()).isZero();
		assertThat(second.replaced()).isZero();
		assertThat(second.upToDate()).isEqualTo(1);
		assertThat(Files.readString(file)).isEqualTo(afterFirst);
	}

	@Test
	void anInterpretationFromAnOlderSchemaIsReplacedRatherThanLeftToRot() throws Exception {
		Path file = write("run.json", oneItemWithVerdict());
		new InterpretationReExport(reading("REJECTED", 1), 1).run(List.of(file), false);

		Report later = new InterpretationReExport(reading("ACCEPTED", 2), 2).run(List.of(file), false);

		assertThat(later.replaced()).isEqualTo(1);
		JsonNode interpretation = JSON.readTree(Files.readString(file)).path("items").get(0).path("interpretation");
		assertThat(interpretation.path("schemaVersion").asInt()).isEqualTo(2);
		assertThat(interpretation.path("reading").asText()).isEqualTo("ACCEPTED");
	}

	@Test
	void anInterpreterWritingADifferentSchemaVersionFailsRatherThanChurn() throws Exception {
		Path file = write("run.json", oneItemWithVerdict());
		String before = Files.readString(file);

		// the library has moved to 2; this tool still believes 1
		Report report = new InterpretationReExport(reading("REJECTED", 2), 1).run(List.of(file), false);

		assertThat(report.filesFailed()).isEqualTo(1);
		assertThat(report.files().get(0).failure()).contains("schemaVersion 2 where 1 was expected");
		assertThat(Files.readString(file)).isEqualTo(before);
	}

	@Test
	void anItemWithNoVerdictIsLeftAlone() throws Exception {
		Path file = write("run.json", """
				{
				  "items" : [ { "itemId" : "a", "passed" : false, "verdict" : null } ]
				}
				""");

		Report report = reExport(reading("REJECTED")).run(List.of(file), false);

		assertThat(report.added()).isZero();
		JsonNode item = JSON.readTree(Files.readString(file)).path("items").get(0);
		assertThat(item.has("interpretation")).isFalse();
	}

	@Test
	void aDryRunReportsEverythingAndWritesNothing() throws Exception {
		Path file = write("run.json", oneItemWithVerdict());
		String before = Files.readString(file);

		Report report = reExport(reading("REJECTED")).run(List.of(file), true);

		assertThat(report.added()).isEqualTo(1);
		assertThat(report.files().get(0).written()).isFalse();
		assertThat(Files.readString(file)).isEqualTo(before);
	}

	@Test
	void anInterpreterThatDisturbsTheVerdictFailsTheFileAndWritesNothing() throws Exception {
		Path file = write("run.json", oneItemWithVerdict());
		String before = Files.readString(file);
		InterpretationReExport.VerdictInterpreter vandal = verdict -> {
			((ObjectNode) verdict).put("aggregated", "trampled");
			return reading("REJECTED").interpret(verdict);
		};

		Report report = new InterpretationReExport(vandal, 1).run(List.of(file), false);

		assertThat(report.filesFailed()).isEqualTo(1);
		assertThat(report.files().get(0).failure()).contains("verdict changed");
		assertThat(Files.readString(file)).isEqualTo(before);
	}

	@Test
	void defectsAreCountedByKindAcrossTheRun() throws Exception {
		Path one = write("one.json", oneItemWithVerdict());
		Path two = write("two.json", oneItemWithVerdict());
		InterpretationReExport.VerdictInterpreter withDefects = verdict -> {
			ObjectNode node = JSON.createObjectNode();
			node.put("schemaVersion", 1);
			node.put("reading", "REJECTED");
			node.putArray("defects")
				.add(JSON.createObjectNode().put("kind", "ABSENT").put("field", "decision"))
				.add(JSON.createObjectNode().put("kind", "ABSENT").put("field", "seats"))
				.add(JSON.createObjectNode().put("kind", "UNPARSEABLE").put("field", "score"));
			return node;
		};

		Report report = new InterpretationReExport(withDefects, 1).run(List.of(one, two), true);

		assertThat(report.defectsByKind()).containsEntry("ABSENT", 4).containsEntry("UNPARSEABLE", 2);
		assertThat(report.summary()).contains("4  ABSENT");
	}

	@Test
	void anUnreadableFileIsReportedRatherThanSkipped() throws Exception {
		Path file = write("broken.json", "{ this is not json");

		Report report = reExport(reading("REJECTED")).run(List.of(file), false);

		assertThat(report.filesFailed()).isEqualTo(1);
		assertThat(report.files().get(0).failure()).startsWith("unreadable");
	}

	private InterpretationReExport reExport(InterpretationReExport.VerdictInterpreter interpreter) {
		return new InterpretationReExport(interpreter, 1);
	}

	private static InterpretationReExport.VerdictInterpreter reading(String reading) {
		return reading(reading, 1);
	}

	private static InterpretationReExport.VerdictInterpreter reading(String reading, int schemaVersion) {
		return verdict -> {
			ObjectNode node = JSON.createObjectNode();
			node.put("schemaVersion", schemaVersion);
			node.put("reading", reading);
			node.putArray("defects");
			return node;
		};
	}

	private static String oneItemWithVerdict() {
		return """
				{
				  "items" : [ {
				    "itemId" : "a",
				    "verdict" : { "aggregated" : { "status" : "fail" } }
				  } ]
				}
				""";
	}

	private Path write(String name, String content) throws Exception {
		Path file = this.dir.resolve(name);
		Files.writeString(file, content);
		return file;
	}

}
