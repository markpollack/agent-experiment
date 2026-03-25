package io.github.markpollack.experiment.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.github.markpollack.experiment.agent.InvocationResult;
import io.github.markpollack.experiment.agent.MockAgentInvoker;
import io.github.markpollack.experiment.dataset.DatasetItem;
import io.github.markpollack.experiment.dataset.DatasetManager;
import io.github.markpollack.experiment.dataset.FileSystemDatasetManager;
import io.github.markpollack.experiment.dataset.ItemFilter;
import io.github.markpollack.experiment.result.ExperimentResult;
import io.github.markpollack.experiment.result.ItemResult;
import io.github.markpollack.experiment.store.ActiveSession;
import io.github.markpollack.experiment.store.InMemoryResultStore;
import io.github.markpollack.experiment.store.InMemorySessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.judge.Judge;
import org.springaicommunity.judge.context.JudgmentContext;
import org.springaicommunity.judge.jury.SimpleJury;
import org.springaicommunity.judge.jury.Jury;
import org.springaicommunity.judge.result.Judgment;
import org.springaicommunity.judge.result.JudgmentStatus;
import org.springaicommunity.judge.score.BooleanScore;

import static org.assertj.core.api.Assertions.assertThat;

class ExperimentRunnerTest {

	private static final Path TEST_DATASET = Path.of("src/test/resources/test-dataset").toAbsolutePath();

	private DatasetManager datasetManager;

	private InMemoryResultStore resultStore;

	private MockAgentInvoker mockAgent;

	@BeforeEach
	void setUp() {
		datasetManager = new FileSystemDatasetManager();
		resultStore = new InMemoryResultStore();
		mockAgent = new MockAgentInvoker();
	}

	@Test
	void runExecutesFullPipeline() {
		Jury jury = juryWith(passingJudge("test_judge"));
		ExperimentConfig config = defaultConfig().build();
		ExperimentRunner runner = new ExperimentRunner(datasetManager, jury, resultStore, config);

		ExperimentResult result = runner.run(mockAgent);

		assertThat(result.experimentName()).isEqualTo("test-experiment");
		assertThat(result.items()).hasSizeGreaterThan(0);
		assertThat(result.passRate()).isEqualTo(1.0);
		assertThat(result.totalCostUsd()).isGreaterThan(0);
		assertThat(result.totalTokens()).isGreaterThan(0);
		assertThat(result.totalDurationMs()).isGreaterThan(0);

		// Result was persisted
		assertThat(resultStore.size()).isEqualTo(1);
		assertThat(resultStore.load(result.experimentId())).isPresent();
	}

	@Test
	void runRecordsPerJudgeScoresOnItems() {
		Jury jury = juryWith(passingJudge("build_judge"));
		ExperimentConfig config = defaultConfig().build();
		ExperimentRunner runner = new ExperimentRunner(datasetManager, jury, resultStore, config);

		ExperimentResult result = runner.run(mockAgent);

		for (ItemResult item : result.items()) {
			assertThat(item.scores()).containsKey("build_judge");
			assertThat(item.scores().get("build_judge")).isEqualTo(1.0);
			assertThat(item.verdict()).isNotNull();
		}
	}

	@Test
	void runAggregatesScoresAcrossItems() {
		Jury jury = juryWith(passingJudge("build_judge"));
		ExperimentConfig config = defaultConfig().build();
		ExperimentRunner runner = new ExperimentRunner(datasetManager, jury, resultStore, config);

		ExperimentResult result = runner.run(mockAgent);

		assertThat(result.aggregateScores()).containsKey("build_judge");
		assertThat(result.aggregateScores().get("build_judge")).isEqualTo(1.0);
	}

	@Test
	void itemFailureDoesNotHaltExperiment() {
		// Make first item fail via error InvocationResult, others succeed
		mockAgent.onItem("SIMPLE-001", InvocationResult.error("agent crashed", Map.of("itemId", "SIMPLE-001")));
		Jury jury = juryWith(passingJudge("test_judge"));
		ExperimentConfig config = defaultConfig().build();
		ExperimentRunner runner = new ExperimentRunner(datasetManager, jury, resultStore, config);

		ExperimentResult result = runner.run(mockAgent);

		// Experiment still completes with all items (bucket A has 2 active items)
		assertThat(result.items()).hasSize(2);
		// Failed item is recorded
		ItemResult failedItem = result.items().stream().filter(i -> i.itemId().equals("SIMPLE-001")).findFirst().get();
		assertThat(failedItem.success()).isFalse();
		assertThat(failedItem.passed()).isFalse();
		// Other item still succeeds
		ItemResult otherItem = result.items().stream().filter(i -> i.itemId().equals("SIMPLE-002")).findFirst().get();
		assertThat(otherItem.success()).isTrue();
	}

	@Test
	void runRecordsDatasetVersion() {
		Jury jury = juryWith(passingJudge("test_judge"));
		ExperimentConfig config = defaultConfig().build();
		ExperimentRunner runner = new ExperimentRunner(datasetManager, jury, resultStore, config);

		ExperimentResult result = runner.run(mockAgent);

		assertThat(result.datasetSemanticVersion()).isEqualTo("1.0.0");
	}

	@Test
	void runRecordsMetadata() {
		Jury jury = juryWith(passingJudge("test_judge"));
		ExperimentConfig config = defaultConfig().metadata(Map.of("model_version", "v1")).build();
		ExperimentRunner runner = new ExperimentRunner(datasetManager, jury, resultStore, config);

		ExperimentResult result = runner.run(mockAgent);

		assertThat(result.metadata()).containsEntry("model_version", "v1");
	}

	@Test
	void agentReceivesConstructedPrompt() {
		Jury jury = juryWith(passingJudge("test_judge"));
		ExperimentConfig config = defaultConfig().promptTemplate("Please do: {{task}}").build();
		ExperimentRunner runner = new ExperimentRunner(datasetManager, jury, resultStore, config);

		runner.run(mockAgent);

		assertThat(mockAgent.invocationCount()).isGreaterThan(0);
		// All prompts should start with "Please do: "
		for (var ctx : mockAgent.invocations()) {
			assertThat(ctx.prompt()).startsWith("Please do: ");
		}
	}

	@Test
	void agentReceivesWorkspaceWithBeforeFiles() {
		// Use a handler that checks workspace contents
		mockAgent.defaultResult(ctx -> {
			// Workspace should exist and be a directory
			assertThat(ctx.workspacePath()).isDirectory();
			return InvocationResult.completed(List.of(), 100, 200, 50, 0.01, 1000, null, ctx.metadata());
		});
		Jury jury = juryWith(passingJudge("test_judge"));
		ExperimentConfig config = defaultConfig().build();
		ExperimentRunner runner = new ExperimentRunner(datasetManager, jury, resultStore, config);

		ExperimentResult result = runner.run(mockAgent);

		assertThat(result.items()).allMatch(ItemResult::success);
	}

	@Test
	void buildPromptReplacesTaskPlaceholder() {
		Jury jury = juryWith(passingJudge("test_judge"));
		ExperimentConfig config = defaultConfig().promptTemplate("Task: {{task}}").build();
		ExperimentRunner runner = new ExperimentRunner(datasetManager, jury, resultStore, config);

		// Use a known item to test prompt construction
		var dataset = datasetManager.load(TEST_DATASET);
		var items = datasetManager.activeItems(dataset);
		DatasetItem item = items.get(0);

		String prompt = runner.buildPrompt(item);

		assertThat(prompt).startsWith("Task: ");
		assertThat(prompt).doesNotContain("{{task}}");
		assertThat(prompt).contains(item.developerTask());
	}

	@Test
	void judgingRunsWhenReferenceDirIsNull() {
		// GITREF-001 has SourceRefs but no physical reference/ dir — referenceDir
		// resolves to null
		// Previously this would skip judging entirely; now judges still execute
		Jury jury = juryWith(passingJudge("build_judge"));
		ExperimentConfig config = defaultConfig().itemFilter(ItemFilter.bucket("B")).build();
		ExperimentRunner runner = new ExperimentRunner(datasetManager, jury, resultStore, config);

		ExperimentResult result = runner.run(mockAgent);

		// All bucket-B active items should have results
		assertThat(result.items()).hasSizeGreaterThan(0);
		// GITREF items (no physical dirs) should still have been judged
		for (ItemResult item : result.items()) {
			if (item.success()) {
				assertThat(item.scores()).containsKey("build_judge");
				assertThat(item.verdict()).isNotNull();
			}
		}
	}

	@Test
	void passingJudgeProducesPassRate1() {
		Jury jury = juryWith(passingJudge("judge"));
		ExperimentConfig config = defaultConfig().build();
		ExperimentRunner runner = new ExperimentRunner(datasetManager, jury, resultStore, config);

		ExperimentResult result = runner.run(mockAgent);

		assertThat(result.passRate()).isEqualTo(1.0);
	}

	@Test
	void failingJudgeProducesPassRate0() {
		Jury jury = juryWith(failingJudge("judge"));
		ExperimentConfig config = defaultConfig().build();
		ExperimentRunner runner = new ExperimentRunner(datasetManager, jury, resultStore, config);

		ExperimentResult result = runner.run(mockAgent);

		assertThat(result.passRate()).isEqualTo(0.0);
		for (ItemResult item : result.items()) {
			if (item.success()) {
				assertThat(item.passed()).isFalse();
				assertThat(item.scores().get("judge")).isEqualTo(0.0);
			}
		}
	}

	@Test
	void preserveWorkspacesMovesWorkspaceToOutputDir(@TempDir Path outputDir) {
		Jury jury = juryWith(passingJudge("test_judge"));
		ExperimentConfig config = defaultConfig().preserveWorkspaces(true).outputDir(outputDir).build();
		ExperimentRunner runner = new ExperimentRunner(datasetManager, jury, resultStore, config);

		ExperimentResult result = runner.run(mockAgent);

		for (ItemResult item : result.items()) {
			assertThat(item.workspacePath()).isNotNull();
			assertThat(item.workspacePath()).isDirectory();
			assertThat(item.workspacePath().getParent().getFileName().toString()).isEqualTo("workspaces");
			assertThat(item.workspacePath().getFileName().toString()).isEqualTo(item.itemSlug());
		}
	}

	@Test
	void workspaceDeletedWhenPreserveDisabled() {
		Jury jury = juryWith(passingJudge("test_judge"));
		ExperimentConfig config = defaultConfig().preserveWorkspaces(false).build();
		ExperimentRunner runner = new ExperimentRunner(datasetManager, jury, resultStore, config);

		ExperimentResult result = runner.run(mockAgent);

		for (ItemResult item : result.items()) {
			assertThat(item.workspacePath()).isNull();
		}
	}

	@Test
	void failedItemPreservesWorkspace(@TempDir Path outputDir) {
		mockAgent.onItem("SIMPLE-001", InvocationResult.error("agent crashed", Map.of("itemId", "SIMPLE-001")));
		Jury jury = juryWith(passingJudge("test_judge"));
		ExperimentConfig config = defaultConfig().preserveWorkspaces(true).outputDir(outputDir).build();
		ExperimentRunner runner = new ExperimentRunner(datasetManager, jury, resultStore, config);

		ExperimentResult result = runner.run(mockAgent);

		ItemResult failedItem = result.items().stream().filter(i -> i.itemId().equals("SIMPLE-001")).findFirst().get();
		assertThat(failedItem.success()).isFalse();
		// Failed items still get preserved workspaces
		assertThat(failedItem.workspacePath()).isNotNull();
		assertThat(failedItem.workspacePath()).isDirectory();
	}

	@Test
	void runWithSession_savesToSessionStore() {
		InMemorySessionStore sessionStore = new InMemorySessionStore();
		sessionStore.createSession("suite-1", "test-experiment", Map.of());
		ActiveSession activeSession = new ActiveSession("suite-1", "test-experiment", "control");

		Jury jury = juryWith(passingJudge("test_judge"));
		ExperimentConfig config = defaultConfig().build();
		ExperimentRunner runner = new ExperimentRunner(datasetManager, jury, resultStore, sessionStore, config);

		runner.run(mockAgent, activeSession);

		var session = sessionStore.loadSession("test-experiment", "suite-1").orElseThrow();
		assertThat(session.variants()).hasSize(1);
		assertThat(session.variants().get(0).variantName()).isEqualTo("control");
	}

	@Test
	void runWithSession_workspacesInSessionDir(@TempDir Path outputDir) {
		InMemorySessionStore sessionStore = new InMemorySessionStore();
		sessionStore.createSession("suite-1", "test-experiment", Map.of());
		ActiveSession activeSession = new ActiveSession("suite-1", "test-experiment", "control");

		Jury jury = juryWith(passingJudge("test_judge"));
		ExperimentConfig config = defaultConfig().preserveWorkspaces(true).outputDir(outputDir).build();
		ExperimentRunner runner = new ExperimentRunner(datasetManager, jury, resultStore, sessionStore, config);

		ExperimentResult result = runner.run(mockAgent, activeSession);

		for (ItemResult item : result.items()) {
			assertThat(item.workspacePath()).isNotNull();
			assertThat(item.workspacePath().toString()).contains("sessions/suite-1/workspaces/control/");
		}
	}

	@Test
	void runWithSession_runDirInSessionDir(@TempDir Path outputDir) {
		InMemorySessionStore sessionStore = new InMemorySessionStore();
		sessionStore.createSession("suite-1", "test-experiment", Map.of());
		ActiveSession activeSession = new ActiveSession("suite-1", "test-experiment", "control");

		Jury jury = juryWith(passingJudge("test_judge"));
		ExperimentConfig config = defaultConfig().outputDir(outputDir).build();
		ExperimentRunner runner = new ExperimentRunner(datasetManager, jury, resultStore, sessionStore, config);

		runner.run(mockAgent, activeSession);

		// Verify run.log was written under session dir
		Path expectedRunDir = outputDir.resolve("test-experiment/sessions/suite-1/control");
		assertThat(expectedRunDir).isDirectory();
	}

	@Test
	void runWithNullSession_behaviorUnchanged() {
		Jury jury = juryWith(passingJudge("test_judge"));
		ExperimentConfig config = defaultConfig().build();
		// Use 4-arg constructor + 1-arg run — identical to pre-session behavior
		ExperimentRunner runner = new ExperimentRunner(datasetManager, jury, resultStore, config);

		ExperimentResult result = runner.run(mockAgent);

		assertThat(result.experimentName()).isEqualTo("test-experiment");
		assertThat(result.items()).hasSizeGreaterThan(0);
		assertThat(resultStore.size()).isEqualTo(1);
	}

	private static ExperimentConfig.Builder defaultConfig() {
		return ExperimentConfig.builder()
			.experimentName("test-experiment")
			.datasetDir(TEST_DATASET)
			.itemFilter(ItemFilter.bucket("A"))
			.model("sonnet")
			.promptTemplate("{{task}}")
			.perItemTimeout(Duration.ofSeconds(30));
	}

	private static Jury juryWith(Judge judge) {
		return SimpleJury.builder()
			.judge(judge, 1.0)
			.votingStrategy(new org.springaicommunity.judge.jury.MajorityVotingStrategy())
			.build();
	}

	private static Judge passingJudge(String name) {
		return new NamedJudge(name, true);
	}

	private static Judge failingJudge(String name) {
		return new NamedJudge(name, false);
	}

	/**
	 * Simple named judge for testing that always passes or always fails.
	 */
	private static class NamedJudge implements Judge, org.springaicommunity.judge.JudgeWithMetadata {

		private final org.springaicommunity.judge.JudgeMetadata metadata;

		private final boolean pass;

		NamedJudge(String name, boolean pass) {
			this.metadata = new org.springaicommunity.judge.JudgeMetadata(name, "Test judge: " + name,
					org.springaicommunity.judge.JudgeType.DETERMINISTIC);
			this.pass = pass;
		}

		@Override
		public Judgment judge(JudgmentContext context) {
			return Judgment.builder()
				.score(new BooleanScore(pass))
				.status(pass ? JudgmentStatus.PASS : JudgmentStatus.FAIL)
				.reasoning(pass ? "Passed" : "Failed")
				.build();
		}

		@Override
		public org.springaicommunity.judge.JudgeMetadata metadata() {
			return metadata;
		}

	}

}
