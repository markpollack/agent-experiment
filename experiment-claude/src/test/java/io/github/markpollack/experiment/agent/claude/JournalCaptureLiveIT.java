package io.github.markpollack.experiment.agent.claude;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.experiment.agent.InvocationContext;
import io.github.markpollack.experiment.agent.InvocationResult;
import io.github.markpollack.experiment.journal.ExperimentJournal;
import io.github.markpollack.experiment.journal.RunJournal;
import io.github.markpollack.journal.claude.PhaseCapture;
import io.github.markpollack.journal.claude.ToolUseRecord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Live end-to-end guard for the cross-repo journal seam (slice 2 → agent-control-theory
 * slice 4).
 *
 * <p>
 * The mock-based {@code AgentExperimentJournalTest} proves the journal <em>plumbing</em>;
 * this test proves the part a mock cannot: that a <strong>real</strong>
 * {@link PhaseCapture} — parsed from the live Claude wire by {@code SessionLogParser} —
 * carries populated {@code turns[]} + {@code toolUses} so the exact slice-2 journal path
 * emits real per-tool {@code StepCostEvent}s keyed by the tool_use id. This is the format
 * agent-control-theory (slice 4) pins its glob and join against, so it is worth
 * confirming against a live agent before that dependency hardens.
 *
 * <p>
 * Requires the Claude CLI + API key. Excluded from the normal build (tagged
 * {@code integration}); run with:
 * {@code CLAUDECODE= ./mvnw test -pl experiment-claude -Dsurefire.excludedGroups= -Dgroups=integration -Dtest=JournalCaptureLiveIT}
 */
@Tag("integration")
class JournalCaptureLiveIT {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Test
	void liveRunEmitsCanonicalJournalWithToolUseKeyedStepCosts(@TempDir Path tmp) throws Exception {
		Path workspace = Files.createDirectories(tmp.resolve("workspace"));
		Path journalRoot = ExperimentJournal.journalRoot(tmp.resolve("results-run"));

		// A prompt that forces a real tool call (Write) so the capture carries tool_use
		// ids.
		ClaudeSdkInvokerConfig config = ClaudeSdkInvokerConfig.builder().maxTurns(6).maxBudgetUsd(0.10).build();
		ClaudeSdkInvoker invoker = new ClaudeSdkInvoker(config);
		InvocationContext context = InvocationContext.builder()
			.workspacePath(workspace)
			.prompt("Create a file named greeting.txt in the current directory containing exactly the word: hello. "
					+ "Then reply done.")
			.model("haiku")
			.timeout(Duration.ofSeconds(120))
			.metadata(Map.of("itemId", "LIVE-001", "itemSlug", "live-item"))
			.build();

		InvocationResult result = invoker.invoke(context);
		assertThat(result.success()).as("live invocation should succeed").isTrue();

		// The tool_use ids the real capture carries — the join keys ACT relies on.
		Set<String> toolUseIds = new LinkedHashSet<>();
		for (PhaseCapture phase : result.phases()) {
			for (ToolUseRecord tu : phase.toolUses()) {
				toolUseIds.add(tu.id());
			}
		}
		assertThat(toolUseIds).as("live run should have made at least one tool call").isNotEmpty();

		// Drive the EXACT slice-2 journal path with the real capture.
		ExperimentJournal journal = ExperimentJournal.open(journalRoot, "live-journal-exp");
		try (RunJournal run = journal.openItem("LIVE-001", "live-item", "haiku", "default", null, result.sessionId())) {
			for (PhaseCapture phase : result.phases()) {
				run.recordPhase(phase);
			}
			run.finish();
		}

		Path runDir = single(journalRoot, "run.json").getParent();
		Path eventsFile = runDir.resolve("events.jsonl");
		Path analysisFile = runDir.resolve("analysis.jsonl");

		// --- run.json: the ACT join surface ---
		JsonNode runCfg = MAPPER.readTree(runDir.resolve("run.json").toFile()).get("config");
		assertThat(runCfg.get("variant").asText()).isEqualTo("default");
		assertThat(runCfg.get("itemId").asText()).isEqualTo("LIVE-001");
		assertThat(runCfg.get("itemSlug").asText()).isEqualTo("live-item");
		assertThat(runCfg.get("model").asText()).isEqualTo("haiku");

		// --- A5 headers open each stream ---
		assertThat(header(eventsFile)).isEqualTo("events");
		assertThat(header(analysisFile)).isEqualTo("analysis");

		// --- events.jsonl carries the tool_use ids (immutable execution log) ---
		String eventsText = Files.readString(eventsFile);
		for (String id : toolUseIds) {
			assertThat(eventsText).as("events.jsonl should carry tool_use id %s", id).contains(id);
		}

		// --- analysis.jsonl: real StepCostEvents keyed by tool_use id ---
		List<JsonNode> stepCosts = stepCosts(analysisFile);
		assertThat(stepCosts).as("real run should emit per-step cost events").isNotEmpty();

		Set<String> stepIds = new LinkedHashSet<>();
		double attributedSum = 0.0;
		double runTotal = 0.0;
		for (JsonNode sc : stepCosts) {
			stepIds.add(sc.get("stepId").asText());
			assertThat(sc.get("attributionMethod").asText()).isIn("OUTPUT_TOKEN_PROPORTIONAL", "EVEN_SPLIT");
			attributedSum += sc.get("attributedCostUsd").asDouble();
			runTotal = sc.get("actualRunCostUsd").asDouble();
		}
		// The tool_use ids are present as step ids — the cross-repo join holds on real
		// data.
		assertThat(stepIds).as("every tool_use id should appear as a StepCostEvent stepId").containsAll(toolUseIds);
		// Per-step shares reconstruct the run total (residual folded into the last step).
		assertThat(attributedSum).isEqualTo(runTotal, within(1e-9));

		System.out.printf("LIVE journal OK: %d tool_use id(s), %d step_cost event(s), runTotal=$%.6f, dir=%s%n",
				toolUseIds.size(), stepCosts.size(), runTotal, runDir);
	}

	/** The single file with the given name anywhere under root (the one run's file). */
	private static Path single(Path root, String fileName) throws Exception {
		try (var walk = Files.walk(root)) {
			List<Path> hits = walk.filter(Files::isRegularFile)
				.filter(p -> p.getFileName().toString().equals(fileName))
				.toList();
			assertThat(hits).as("exactly one %s under %s", fileName, root).hasSize(1);
			return hits.get(0);
		}
	}

	/**
	 * The {@code stream} value of the A5 header on line 0, or null if the first line
	 * isn't a header.
	 */
	private static String header(Path jsonl) throws Exception {
		JsonNode node = MAPPER.readTree(Files.readAllLines(jsonl).get(0));
		return "header".equals(node.path("@type").asText()) ? node.get("stream").asText() : null;
	}

	private static List<JsonNode> stepCosts(Path analysisFile) throws Exception {
		List<JsonNode> out = new ArrayList<>();
		for (String line : Files.readAllLines(analysisFile)) {
			if (line.isBlank()) {
				continue;
			}
			JsonNode node = MAPPER.readTree(line);
			if ("step_cost".equals(node.path("@type").asText())) {
				out.add(node);
			}
		}
		return out;
	}

}
