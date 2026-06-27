package io.github.markpollack.experiment.runner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.experiment.agent.InvocationResult;
import io.github.markpollack.experiment.agent.MockAgentInvoker;
import io.github.markpollack.experiment.dataset.DatasetManager;
import io.github.markpollack.experiment.dataset.FileSystemDatasetManager;
import io.github.markpollack.experiment.dataset.ItemFilter;
import io.github.markpollack.experiment.result.ExperimentResult;
import io.github.markpollack.experiment.store.InMemoryResultStore;
import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.claude.PhaseCapture;
import io.github.markpollack.journal.claude.ToolUseRecord;
import io.github.markpollack.journal.claude.TurnUsage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.markpollack.judge.Judge;
import io.github.markpollack.judge.JudgeMetadata;
import io.github.markpollack.judge.JudgeType;
import io.github.markpollack.judge.JudgeWithMetadata;
import io.github.markpollack.judge.context.JudgmentContext;
import io.github.markpollack.judge.jury.Jury;
import io.github.markpollack.judge.jury.MajorityVotingStrategy;
import io.github.markpollack.judge.jury.SimpleJury;
import io.github.markpollack.judge.result.Judgment;
import io.github.markpollack.judge.result.JudgmentStatus;
import io.github.markpollack.judge.score.BooleanScore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance test for the experiment-core slice of the first-class journal-capture
 * feature: a vanilla experiment run leaves an {@code analysis.jsonl} with
 * {@code StepCostEvent}s keyed by tool_use id — with <strong>zero invoker
 * changes</strong> (the invoker just returns {@link PhaseCapture};
 * {@link MockAgentInvoker} carries no journaling code).
 */
class AgentExperimentJournalTest {

	private static final Path TEST_DATASET = Path.of("src/test/resources/test-dataset").toAbsolutePath();

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private DatasetManager datasetManager;

	private InMemoryResultStore resultStore;

	private MockAgentInvoker mockAgent;

	@BeforeEach
	void setUp() {
		datasetManager = new FileSystemDatasetManager();
		resultStore = new InMemoryResultStore();
		mockAgent = new MockAgentInvoker();
	}

	@AfterEach
	void tearDown() {
		// Journal context (storage + experiment registry) is process-global; reset
		// between tests.
		Journal.reset();
	}

	@Test
	void vanillaRunWritesAnalysisJsonlWithStepCostsKeyedByToolUseId(@TempDir Path outputDir) throws Exception {
		// The invoker stays dumb: it returns a PhaseCapture with two tool uses and
		// per-turn usage.
		mockAgent.defaultResult(
				ctx -> InvocationResult.fromPhases(List.of(twoToolPhase()), 1500, "session-abc", ctx.metadata()));

		ExperimentConfig config = baseConfig().outputDir(outputDir).build();
		AgentExperiment runner = new AgentExperiment(datasetManager, passingJury(), resultStore, config);

		ExperimentResult result = runner.run(mockAgent);
		assertThat(result.items()).allMatch(i -> i.success());

		List<JsonNode> stepCosts = readAllStepCosts(outputDir);
		// 2 active items in bucket A × 2 tool steps each.
		assertThat(stepCosts).hasSize(4);
		assertThat(stepCosts).allSatisfy(node -> {
			assertThat(node.get("@type").asText()).isEqualTo("step_cost");
			assertThat(node.get("attributionMethod").asText()).isEqualTo("OUTPUT_TOKEN_PROPORTIONAL");
			assertThat(node.has("attributedCostUsd")).isTrue();
		});
		// Keyed by tool_use id — both ids present across the steps.
		assertThat(stepCosts).map(n -> n.get("stepId").asText()).contains("toolu_01", "toolu_02");

		// The immutable execution log is written too, carrying the same tool_use ids.
		List<Path> events = findFiles(outputDir, "events.jsonl");
		assertThat(events).isNotEmpty();
		assertThat(Files.readString(events.get(0))).contains("toolu_01").contains("toolu_02");
	}

	@Test
	void perStepCostsSumToRunTotal(@TempDir Path outputDir) throws Exception {
		mockAgent.defaultResult(
				ctx -> InvocationResult.fromPhases(List.of(twoToolPhase()), 1500, "session-abc", ctx.metadata()));
		ExperimentConfig config = baseConfig().itemFilter(ItemFilter.bucket("A")).outputDir(outputDir).build();
		AgentExperiment runner = new AgentExperiment(datasetManager, passingJury(), resultStore, config);

		runner.run(mockAgent);

		// Each run's per-step attributedCostUsd sums to the run total (±float rounding).
		for (Path analysis : findFiles(outputDir, "analysis.jsonl")) {
			double sum = 0.0;
			double runTotal = 0.0;
			for (String line : Files.readAllLines(analysis)) {
				if (line.isBlank()) {
					continue;
				}
				JsonNode node = MAPPER.readTree(line);
				sum += node.get("attributedCostUsd").asDouble();
				runTotal = node.get("actualRunCostUsd").asDouble();
			}
			assertThat(sum).isEqualTo(runTotal, org.assertj.core.api.Assertions.within(1e-9));
		}
	}

	@Test
	void withoutJournalWritesNoJournal(@TempDir Path outputDir) {
		mockAgent.defaultResult(
				ctx -> InvocationResult.fromPhases(List.of(twoToolPhase()), 1500, "session-abc", ctx.metadata()));
		ExperimentConfig config = baseConfig().outputDir(outputDir).withoutJournal().build();
		AgentExperiment runner = new AgentExperiment(datasetManager, passingJury(), resultStore, config);

		runner.run(mockAgent);

		assertThat(findFiles(outputDir, "analysis.jsonl")).isEmpty();
		assertThat(findFiles(outputDir, "events.jsonl")).isEmpty();
	}

	@Test
	void runWithoutOutputDirCompletesWithoutJournal() {
		// No outputDir → nowhere durable to write; the run still completes (journaling
		// no-ops).
		mockAgent.defaultResult(
				ctx -> InvocationResult.fromPhases(List.of(twoToolPhase()), 1500, "session-abc", ctx.metadata()));
		ExperimentConfig config = baseConfig().build();
		AgentExperiment runner = new AgentExperiment(datasetManager, passingJury(), resultStore, config);

		ExperimentResult result = runner.run(mockAgent);

		assertThat(result.items()).allMatch(i -> i.success());
	}

	private List<JsonNode> readAllStepCosts(Path outputDir) throws Exception {
		List<JsonNode> nodes = new java.util.ArrayList<>();
		for (Path analysis : findFiles(outputDir, "analysis.jsonl")) {
			for (String line : Files.readAllLines(analysis)) {
				if (!line.isBlank()) {
					nodes.add(MAPPER.readTree(line));
				}
			}
		}
		return nodes;
	}

	private static List<Path> findFiles(Path root, String fileName) {
		try (Stream<Path> walk = Files.walk(root)) {
			return walk.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().equals(fileName)).toList();
		}
		catch (java.io.IOException ex) {
			throw new java.io.UncheckedIOException(ex);
		}
	}

	/**
	 * A phase with two parallel tool calls and per-turn usage → OUTPUT_TOKEN_PROPORTIONAL
	 * split.
	 */
	private static PhaseCapture twoToolPhase() {
		ToolUseRecord read = new ToolUseRecord("toolu_01", "Read", Map.of("file_path", "Foo.java"));
		ToolUseRecord bash = new ToolUseRecord("toolu_02", "Bash", Map.of("command", "ls"));
		TurnUsage turn = new TurnUsage("msg_1", "claude-opus-4-8", 1000, 200, 0, 0, List.of("toolu_01", "toolu_02"));
		return new PhaseCapture("explore", "do the task", 1000, 200, 0, 0, 0, 1500L, 1200L, 0.02, "session-abc", 1,
				false, "all done", List.of(), List.of(read, bash), "result text", List.of(), List.of(turn), List.of());
	}

	private static ExperimentConfig.Builder baseConfig() {
		return ExperimentConfig.builder()
			.experimentName("journal-test-experiment")
			.datasetDir(TEST_DATASET)
			.itemFilter(ItemFilter.bucket("A"))
			.model("opus")
			.promptTemplate("{{task}}")
			.perItemTimeout(Duration.ofSeconds(30));
	}

	private static Jury passingJury() {
		return SimpleJury.builder()
			.judge(new NamedJudge("test_judge"), 1.0)
			.votingStrategy(new MajorityVotingStrategy())
			.build();
	}

	private static final class NamedJudge implements Judge, JudgeWithMetadata {

		private final JudgeMetadata metadata;

		NamedJudge(String name) {
			this.metadata = new JudgeMetadata(name, "Test judge: " + name, JudgeType.DETERMINISTIC);
		}

		@Override
		public Judgment judge(JudgmentContext context) {
			return Judgment.builder()
				.score(new BooleanScore(true))
				.status(JudgmentStatus.PASS)
				.reasoning("Passed")
				.build();
		}

		@Override
		public JudgeMetadata metadata() {
			return metadata;
		}

	}

}
