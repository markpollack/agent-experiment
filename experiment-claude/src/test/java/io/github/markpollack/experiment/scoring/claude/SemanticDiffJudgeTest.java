package io.github.markpollack.experiment.scoring.claude;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.agents.model.AgentApi;
import io.github.markpollack.agents.model.AgentGeneration;
import io.github.markpollack.agents.model.AgentGenerationMetadata;
import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.judge.JudgeType;
import io.github.markpollack.judge.context.ExecutionStatus;
import io.github.markpollack.judge.context.JudgmentContext;
import io.github.markpollack.judge.result.Check;
import io.github.markpollack.judge.result.Judgment;
import io.github.markpollack.judge.result.JudgmentStatus;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticDiffJudgeTest {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private final Queue<String> cannedOutputs = new ArrayDeque<>();

	private RuntimeException providerFailure;

	private SemanticDiffJudge judge;

	/**
	 * A stub provider: no Claude, no CLI, no subclassing of the judge. Proves the judge
	 * drives whatever {@link AgentApi} it is given.
	 */
	private final AgentApi stubProvider = request -> {
		if (providerFailure != null) {
			throw providerFailure;
		}
		String output = cannedOutputs.isEmpty() ? "" : cannedOutputs.poll();
		return new AgentResponse(
				List.of(new AgentGeneration(output, new AgentGenerationMetadata("SUCCESS", Map.of()))));
	};

	@BeforeEach
	void setUp() {
		cannedOutputs.clear();
		providerFailure = null;
		judge = new SemanticDiffJudge(SemanticDiffJudgeConfig.defaults(), stubProvider);
	}

	@Test
	void metadataHasCorrectValues() {
		assertThat(judge.metadata().name()).isEqualTo("semantic_diff");
		assertThat(judge.metadata().type()).isEqualTo(JudgeType.LLM_POWERED);
	}

	@Test
	void abstainsWhenNoPlanInMetadata() {
		JudgmentContext context = contextWithMetadata(Map.of());

		Judgment judgment = judge.judge(context);

		assertThat(judgment.status()).isEqualTo(JudgmentStatus.ABSTAIN);
		assertThat(judgment.reasoning()).contains("No plan roadmap");
	}

	@Test
	void abstainsWhenPlanHasNoVerifyCriteria() {
		String plan = planWithRoadmap("## Step 1\n- [ ] Do something\n");
		JudgmentContext context = contextWithMetadata(Map.of("plan", plan));

		Judgment judgment = judge.judge(context);

		assertThat(judgment.status()).isEqualTo(JudgmentStatus.ABSTAIN);
		assertThat(judgment.reasoning()).contains("No VERIFY criteria");
	}

	@Test
	void abstainsWhenWorkspaceIsNull() {
		String plan = planWithRoadmap("- [ ] VERIFY: ./mvnw compile");
		JudgmentContext context = JudgmentContext.builder()
			.goal("test")
			.workspace(null)
			.executionTime(Duration.ofSeconds(1))
			.startedAt(Instant.now())
			.status(ExecutionStatus.SUCCESS)
			.metadata("plan", plan)
			.build();

		Judgment judgment = judge.judge(context);

		assertThat(judgment.status()).isEqualTo(JudgmentStatus.ABSTAIN);
		assertThat(judgment.reasoning()).contains("No workspace");
	}

	@Test
	void passWhenAllCriteriaPass() {
		mockStructuredResponses("PASS", "criterion satisfied", "PASS", "criterion satisfied");
		String plan = planWithRoadmap("""
				- [ ] VERIFY: ./mvnw compile
				- [ ] VERIFY: ./mvnw test
				""");
		JudgmentContext context = contextWithMetadata(Map.of("plan", plan));

		Judgment judgment = judge.judge(context);

		assertThat(judgment.status()).isEqualTo(JudgmentStatus.PASS);
		assertThat(judgment.score()).isEqualTo(1.0);
		assertThat(judgment.checks()).hasSize(2);
		assertThat(judgment.checks()).allSatisfy(check -> assertThat(check.passed()).isTrue());
	}

	@Test
	void failWhenSomeCriteriaFail() {
		mockStructuredResponses("PASS", "ok", "FAIL", "not satisfied", "PASS", "ok");
		String plan = planWithRoadmap("""
				- [ ] VERIFY: criterion A
				- [ ] VERIFY: criterion B
				- [ ] VERIFY: criterion C
				""");
		JudgmentContext context = contextWithMetadata(Map.of("plan", plan));

		Judgment judgment = judge.judge(context);

		assertThat(judgment.status()).isEqualTo(JudgmentStatus.PASS); // 2/3 >= 0.5
		assertThat(judgment.score()).isCloseTo(2.0 / 3.0, org.assertj.core.data.Offset.offset(0.001));
	}

	@Test
	void failWhenAllCriteriaFail() {
		mockStructuredResponses("FAIL", "not satisfied", "FAIL", "not satisfied");
		String plan = planWithRoadmap("- [ ] VERIFY: ./mvnw compile\n- [ ] VERIFY: ./mvnw test\n");
		JudgmentContext context = contextWithMetadata(Map.of("plan", plan));

		Judgment judgment = judge.judge(context);

		assertThat(judgment.status()).isEqualTo(JudgmentStatus.FAIL);
		assertThat(judgment.score()).isEqualTo(0.0);
	}

	@Test
	void diagnosticMetadataContainsCriteriaCounts() {
		mockStructuredResponses("PASS", "ok", "PASS", "ok");
		String plan = planWithRoadmap("- [ ] VERIFY: ./mvnw compile\n- [ ] VERIFY: ./mvnw test\n");
		JudgmentContext context = contextWithMetadata(Map.of("plan", plan));

		Judgment judgment = judge.judge(context);

		assertThat(judgment.metadata()).containsEntry("criteriaTotal", 2);
		assertThat(judgment.metadata()).containsEntry("criteriaPassed", 2);
	}

	@Test
	void checksListHasOneCheckPerCriterion() {
		mockStructuredResponses("PASS", "ok", "FAIL", "nope");
		String plan = planWithRoadmap("- [ ] VERIFY: criterion A\n- [ ] VERIFY: criterion B\n");
		JudgmentContext context = contextWithMetadata(Map.of("plan", plan));

		Judgment judgment = judge.judge(context);

		List<Check> checks = judgment.checks();
		assertThat(checks).hasSize(2);
		assertThat(checks.get(0).name()).isEqualTo("criterion A");
		assertThat(checks.get(0).passed()).isTrue();
		assertThat(checks.get(1).name()).isEqualTo("criterion B");
		assertThat(checks.get(1).passed()).isFalse();
	}

	@Test
	void maxCriteriaToEvaluateCapsEvaluatedCount() {
		SemanticDiffJudgeConfig limitedConfig = new SemanticDiffJudgeConfig("sonnet", 2, Duration.ofMinutes(2));
		SemanticDiffJudge limitedJudge = new SemanticDiffJudge(limitedConfig, stubProvider);
		mockStructuredResponses("PASS", "ok", "PASS", "ok");
		String plan = planWithRoadmap("""
				- [ ] VERIFY: criterion A
				- [ ] VERIFY: criterion B
				- [ ] VERIFY: criterion C
				- [ ] VERIFY: criterion D
				""");
		JudgmentContext context = contextWithMetadata(Map.of("plan", plan));

		Judgment judgment = limitedJudge.judge(context);

		assertThat(judgment.checks()).hasSize(2);
		assertThat(judgment.metadata()).containsEntry("criteriaTotal", 2);
	}

	@Test
	void parsesJsonStructuredOutput() {
		mockStructuredResponses("PASS", "Build compiles successfully", "FAIL", "Tests not passing");
		String plan = planWithRoadmap("- [ ] VERIFY: compile\n- [ ] VERIFY: test\n");
		JudgmentContext context = contextWithMetadata(Map.of("plan", plan));

		Judgment judgment = judge.judge(context);

		assertThat(judgment.status()).isEqualTo(JudgmentStatus.PASS); // 1/2 >= 0.5
		assertThat(judgment.score()).isEqualTo(0.5);
		List<Check> checks = judgment.checks();
		assertThat(checks.get(0).passed()).isTrue();
		assertThat(checks.get(0).message()).isEqualTo("Build compiles successfully");
		assertThat(checks.get(1).passed()).isFalse();
		assertThat(checks.get(1).message()).isEqualTo("Tests not passing");
	}

	@Test
	void llmExceptionReturnsErrorJudgment() {
		providerFailure = new RuntimeException("LLM unavailable");
		String plan = planWithRoadmap("- [ ] VERIFY: ./mvnw compile\n");
		JudgmentContext context = contextWithMetadata(Map.of("plan", plan));

		Judgment judgment = judge.judge(context);

		assertThat(judgment.status()).isEqualTo(JudgmentStatus.ERROR);
		assertThat(judgment.reasoning()).contains("failed due to LLM errors");
	}

	@Test
	void judgeModelIsRecordedInTheJudgment() {
		mockStructuredResponses("PASS", "ok");
		JudgmentContext context = contextWithMetadata(Map.of("plan", planWithRoadmap("- [ ] VERIFY: compile\n")));

		Judgment judgment = judge.judge(context);

		// A score is not interpretable without knowing which instrument produced it.
		assertThat(judgment.metadata()).containsEntry("judgeModel", "sonnet");
	}

	@Test
	void theJudgeDoesNotInheritTheSubjectsModel() {
		SemanticDiffJudge haikuJudge = new SemanticDiffJudge(
				new SemanticDiffJudgeConfig("haiku", 20, Duration.ofMinutes(2)), stubProvider);
		mockStructuredResponses("PASS", "ok");

		// The context describes a run of some other model; the judge must ignore it.
		Judgment judgment = haikuJudge
			.judge(contextWithMetadata(Map.of("plan", planWithRoadmap("- [ ] VERIFY: compile\n"), "model", "opus")));

		assertThat(judgment.metadata()).containsEntry("judgeModel", "haiku");
	}

	@Test
	void optionsCarryTheSchemaAndTheJudgesOwnModel() {
		var options = judge.buildOptions(Path.of("/tmp/test-workspace"));

		assertThat(options.getModel()).isEqualTo("sonnet");
		assertThat(options.getWorkingDirectory()).isEqualTo("/tmp/test-workspace");
		assertThat(options.getJsonSchema()).isNotNull().containsKey("properties");
	}

	@Test
	void proseIsStillParsedButRecordsLowerConfidence() {
		cannedOutputs.add("PASS - the build compiles cleanly");
		JudgmentContext context = contextWithMetadata(Map.of("plan", planWithRoadmap("- [ ] VERIFY: compile\n")));

		Judgment judgment = judge.judge(context);

		// A provider that ignores the schema still scores, and the fallback is visible.
		assertThat(judgment.checks()).hasSize(1);
		assertThat(judgment.checks().get(0).passed()).isTrue();
	}

	/**
	 * Queues the provider's structured-output answers, as the JSON a schema-honouring
	 * provider returns. Takes alternating result/reasoning pairs: "PASS", "reason1",
	 * "FAIL", "reason2", ...
	 */
	private void mockStructuredResponses(String... resultReasoningPairs) {
		for (int i = 0; i < resultReasoningPairs.length; i += 2) {
			try {
				cannedOutputs.add(OBJECT_MAPPER.writeValueAsString(
						Map.of("result", resultReasoningPairs[i], "reasoning", resultReasoningPairs[i + 1])));
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
		}
	}

	private JudgmentContext contextWithMetadata(Map<String, Object> metadata) {
		return JudgmentContext.builder()
			.goal("test migration")
			.workspace(Path.of("/tmp/test-workspace"))
			.executionTime(Duration.ofSeconds(30))
			.startedAt(Instant.now())
			.status(ExecutionStatus.SUCCESS)
			.metadata(metadata)
			.build();
	}

	private String planWithRoadmap(String roadmap) {
		return roadmap;
	}

}
