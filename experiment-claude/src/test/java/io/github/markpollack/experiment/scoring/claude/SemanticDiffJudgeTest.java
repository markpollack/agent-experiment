package io.github.markpollack.experiment.scoring.claude;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.markpollack.experiment.pipeline.ExecutionPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.claude.agent.sdk.ClaudeSyncClient;
import org.springaicommunity.claude.agent.sdk.transport.CLIOptions;
import org.springaicommunity.claude.agent.sdk.types.Message;
import org.springaicommunity.claude.agent.sdk.types.ResultMessage;
import org.springaicommunity.judge.JudgeType;
import org.springaicommunity.judge.context.ExecutionStatus;
import org.springaicommunity.judge.context.JudgmentContext;
import org.springaicommunity.judge.result.Check;
import org.springaicommunity.judge.result.Judgment;
import org.springaicommunity.judge.result.JudgmentStatus;
import org.springaicommunity.judge.score.NumericalScore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SemanticDiffJudgeTest {

	private ClaudeSyncClient mockClient;

	private SemanticDiffJudge judge;

	@BeforeEach
	void setUp() {
		mockClient = mock(ClaudeSyncClient.class);
		SemanticDiffJudgeConfig config = SemanticDiffJudgeConfig.defaults();
		judge = new SemanticDiffJudge(config) {
			@Override
			ClaudeSyncClient buildClient(CLIOptions options, Path workspace) {
				return mockClient;
			}
		};
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
		assertThat(judgment.reasoning()).contains("No execution plan");
	}

	@Test
	void abstainsWhenPlanHasNoVerifyCriteria() {
		ExecutionPlan plan = planWithRoadmap("## Step 1\n- [ ] Do something\n");
		JudgmentContext context = contextWithMetadata(Map.of("plan", plan));

		Judgment judgment = judge.judge(context);

		assertThat(judgment.status()).isEqualTo(JudgmentStatus.ABSTAIN);
		assertThat(judgment.reasoning()).contains("No VERIFY criteria");
	}

	@Test
	void abstainsWhenWorkspaceIsNull() {
		ExecutionPlan plan = planWithRoadmap("- [ ] VERIFY: ./mvnw compile");
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
		ExecutionPlan plan = planWithRoadmap("""
				- [ ] VERIFY: ./mvnw compile
				- [ ] VERIFY: ./mvnw test
				""");
		JudgmentContext context = contextWithMetadata(Map.of("plan", plan));

		Judgment judgment = judge.judge(context);

		assertThat(judgment.status()).isEqualTo(JudgmentStatus.PASS);
		NumericalScore score = (NumericalScore) judgment.score();
		assertThat(score.value()).isEqualTo(1.0);
		assertThat(judgment.checks()).hasSize(2);
		assertThat(judgment.checks()).allSatisfy(check -> assertThat(check.passed()).isTrue());
	}

	@Test
	void failWhenSomeCriteriaFail() {
		mockStructuredResponses("PASS", "ok", "FAIL", "not satisfied", "PASS", "ok");
		ExecutionPlan plan = planWithRoadmap("""
				- [ ] VERIFY: criterion A
				- [ ] VERIFY: criterion B
				- [ ] VERIFY: criterion C
				""");
		JudgmentContext context = contextWithMetadata(Map.of("plan", plan));

		Judgment judgment = judge.judge(context);

		assertThat(judgment.status()).isEqualTo(JudgmentStatus.PASS); // 2/3 >= 0.5
		NumericalScore score = (NumericalScore) judgment.score();
		assertThat(score.value()).isCloseTo(2.0 / 3.0, org.assertj.core.data.Offset.offset(0.001));
	}

	@Test
	void failWhenAllCriteriaFail() {
		mockStructuredResponses("FAIL", "not satisfied", "FAIL", "not satisfied");
		ExecutionPlan plan = planWithRoadmap("- [ ] VERIFY: ./mvnw compile\n- [ ] VERIFY: ./mvnw test\n");
		JudgmentContext context = contextWithMetadata(Map.of("plan", plan));

		Judgment judgment = judge.judge(context);

		assertThat(judgment.status()).isEqualTo(JudgmentStatus.FAIL);
		NumericalScore score = (NumericalScore) judgment.score();
		assertThat(score.value()).isEqualTo(0.0);
	}

	@Test
	void diagnosticMetadataContainsCriteriaCounts() {
		mockStructuredResponses("PASS", "ok", "PASS", "ok");
		ExecutionPlan plan = planWithRoadmap("- [ ] VERIFY: ./mvnw compile\n- [ ] VERIFY: ./mvnw test\n");
		JudgmentContext context = contextWithMetadata(Map.of("plan", plan));

		Judgment judgment = judge.judge(context);

		assertThat(judgment.metadata()).containsEntry("criteriaTotal", 2);
		assertThat(judgment.metadata()).containsEntry("criteriaPassed", 2);
	}

	@Test
	void checksListHasOneCheckPerCriterion() {
		mockStructuredResponses("PASS", "ok", "FAIL", "nope");
		ExecutionPlan plan = planWithRoadmap("- [ ] VERIFY: criterion A\n- [ ] VERIFY: criterion B\n");
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
		SemanticDiffJudge limitedJudge = new SemanticDiffJudge(limitedConfig) {
			@Override
			ClaudeSyncClient buildClient(CLIOptions options, Path workspace) {
				return mockClient;
			}
		};
		mockStructuredResponses("PASS", "ok", "PASS", "ok");
		ExecutionPlan plan = planWithRoadmap("""
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
		ExecutionPlan plan = planWithRoadmap("- [ ] VERIFY: compile\n- [ ] VERIFY: test\n");
		JudgmentContext context = contextWithMetadata(Map.of("plan", plan));

		Judgment judgment = judge.judge(context);

		assertThat(judgment.status()).isEqualTo(JudgmentStatus.PASS); // 1/2 >= 0.5
		NumericalScore score = (NumericalScore) judgment.score();
		assertThat(score.value()).isEqualTo(0.5);
		List<Check> checks = judgment.checks();
		assertThat(checks.get(0).passed()).isTrue();
		assertThat(checks.get(0).message()).isEqualTo("Build compiles successfully");
		assertThat(checks.get(1).passed()).isFalse();
		assertThat(checks.get(1).message()).isEqualTo("Tests not passing");
	}

	@Test
	void llmExceptionReturnsErrorJudgment() {
		when(mockClient.connectAndReceive(anyString())).thenThrow(new RuntimeException("LLM unavailable"));
		ExecutionPlan plan = planWithRoadmap("- [ ] VERIFY: ./mvnw compile\n");
		JudgmentContext context = contextWithMetadata(Map.of("plan", plan));

		Judgment judgment = judge.judge(context);

		assertThat(judgment.status()).isEqualTo(JudgmentStatus.ERROR);
		assertThat(judgment.reasoning()).contains("failed due to LLM errors");
	}

	/**
	 * Mocks connectAndReceive to return ResultMessage with structured output. Takes
	 * alternating result/reasoning pairs: "PASS", "reason1", "FAIL", "reason2", ...
	 */
	private void mockStructuredResponses(String... resultReasoningPairs) {
		List<Iterable<Message>> responses = new ArrayList<>();
		for (int i = 0; i < resultReasoningPairs.length; i += 2) {
			String result = resultReasoningPairs[i];
			String reasoning = resultReasoningPairs[i + 1];
			ResultMessage rm = ResultMessage.builder()
				.structuredOutput(Map.of("result", result, "reasoning", reasoning))
				.build();
			responses.add(List.of(rm));
		}

		@SuppressWarnings("unchecked")
		var stub = when(mockClient.connectAndReceive(anyString()));
		for (Iterable<Message> response : responses) {
			stub = stub.thenReturn(response);
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

	private ExecutionPlan planWithRoadmap(String roadmap) {
		return new ExecutionPlan(roadmap, List.of(), List.of(), 0.0, 0, 0, 0, 0L, null);
	}

}
