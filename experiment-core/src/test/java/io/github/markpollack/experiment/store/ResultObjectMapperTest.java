package io.github.markpollack.experiment.store;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.markpollack.experiment.agent.InvocationResult;
import io.github.markpollack.experiment.result.ExperimentResult;
import io.github.markpollack.experiment.result.ItemResult;
import io.github.markpollack.experiment.result.KnowledgeFileEntry;
import io.github.markpollack.experiment.result.KnowledgeManifest;
import io.github.markpollack.experiment.result.RecordedCheck;
import io.github.markpollack.experiment.result.RecordedJudgment;
import io.github.markpollack.experiment.result.RecordedJudgmentStatus;
import io.github.markpollack.experiment.result.RecordedVerdict;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import io.github.markpollack.judge.jury.CompositeAttempt;
import io.github.markpollack.judge.jury.CompositeFailure;
import io.github.markpollack.judge.jury.CompositeFailureCode;
import io.github.markpollack.judge.jury.CompositeRelation;
import io.github.markpollack.judge.jury.TierPolicy;
import io.github.markpollack.judge.jury.Verdict;
import io.github.markpollack.judge.result.Check;
import io.github.markpollack.judge.result.Judgment;

import static org.assertj.core.api.Assertions.assertThat;

class ResultObjectMapperTest {

	private final ObjectMapper mapper = ResultObjectMapper.create();

	@Test
	void roundTripsExperimentResult() throws Exception {
		ExperimentResult original = minimalResult();

		String json = mapper.writeValueAsString(original);
		ExperimentResult restored = mapper.readValue(json, ExperimentResult.class);

		assertThat(restored.experimentId()).isEqualTo(original.experimentId());
		assertThat(restored.experimentName()).isEqualTo(original.experimentName());
		assertThat(restored.timestamp()).isEqualTo(original.timestamp());
		assertThat(restored.passRate()).isEqualTo(original.passRate());
		assertThat(restored.items()).hasSize(original.items().size());
	}

	@Test
	void readsCompleteExperiment05Judge013FixtureAndWritesNormalizedFormat() throws Exception {
		ExperimentResult restored;
		try (var fixture = getClass().getResourceAsStream("/compatibility/experiment-result-0.5-judge-0.13.json")) {
			assertThat(fixture).isNotNull();
			restored = mapper.readValue(fixture, ExperimentResult.class);
		}

		assertThat(restored.experimentId()).isEqualTo("legacy-exp-013");
		assertThat(restored.items()).hasSize(1);
		ItemResult item = restored.items().getFirst();
		assertThat(item.verdict().aggregated().pass()).isTrue();
		assertThat(item.verdict().individualByName().get("quality").score()).isEqualTo(0.75);
		assertThat(item.verdict().compositeAttempts()).singleElement().satisfies(attempt -> {
			assertThat(attempt.name()).isEqualTo("legacy-sub-verdict-0");
			assertThat(attempt.relation()).isEqualTo("legacy_sub_verdict");
			assertThat(attempt.verdict().aggregated().status()).isEqualTo(RecordedJudgmentStatus.FAIL);
		});
		var judgeDetail = (io.github.markpollack.experiment.judge.JudgeExecutionDetail) item.executionDetail();
		assertThat(judgeDetail.candidateJudgment().label()).isEqualTo("good");

		String normalized = mapper.writeValueAsString(restored);
		assertThat(normalized).contains("\"score\" : 0.75", "\"label\" : \"good\"", "\"compositeAttempts\"");
		assertThat(normalized).doesNotContain("subVerdicts", "allowedValues", "\"min\"", "\"max\"");
	}

	@Test
	void roundTripsRecordedJudgment() throws Exception {
		RecordedJudgment original = new RecordedJudgment(RecordedJudgmentStatus.PASS, 0.82, "relevant",
				"Evidence supports the claim", List.of(RecordedCheck.pass("evidence", "found")),
				Map.of("source", "test"));

		String json = mapper.writeValueAsString(original);
		RecordedJudgment restored = mapper.readValue(json, RecordedJudgment.class);

		assertThat(restored).isEqualTo(original);
	}

	@Test
	void readsLegacyBooleanScoreAsOutcomeOnly() throws Exception {
		String json = """
				{"score":{"value":true},"status":"PASS","reasoning":"ok","checks":[],"metadata":{}}
				""";

		RecordedJudgment restored = mapper.readValue(json, RecordedJudgment.class);

		assertThat(restored.status()).isEqualTo(RecordedJudgmentStatus.PASS);
		assertThat(restored.score()).isNull();
		assertThat(restored.effectiveScore()).hasValue(1.0);
	}

	@Test
	void readsLegacyNumericalScoreAsNormalizedMeasurement() throws Exception {
		String json = """
				{"score":{"value":7.5,"min":0.0,"max":10.0},"status":"PASS","reasoning":"ok","checks":[],"metadata":{}}
				""";

		RecordedJudgment restored = mapper.readValue(json, RecordedJudgment.class);

		assertThat(restored.score()).isEqualTo(0.75);
	}

	@Test
	void rejectsLegacyNumericalScoreWithInvalidRange() {
		String json = """
				{"score":{"value":7.5,"min":10.0,"max":10.0},"status":"PASS","reasoning":"bad","checks":[],"metadata":{}}
				""";

		assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> mapper.readValue(json, RecordedJudgment.class)))
			.hasMessageContaining("non-positive range");
	}

	@Test
	void readsLegacyCategoricalScoreAsLabel() throws Exception {
		String json = """
				{"score":{"value":"good","allowedValues":["good","fair","poor"]},"status":"PASS","reasoning":"ok","checks":[],"metadata":{}}
				""";

		RecordedJudgment restored = mapper.readValue(json, RecordedJudgment.class);

		assertThat(restored.label()).isEqualTo("good");
		assertThat(restored.score()).isNull();
	}

	@Test
	void roundTripsVerdict() throws Exception {
		Judgment judgment = Judgment.builder()
			.pass()
			.reasoning("All checks passed")
			.check(Check.pass("build", "compiled successfully"))
			.build();
		RecordedVerdict original = RecordedVerdict.from(Verdict.builder()
			.aggregated(judgment)
			.individual(List.of(judgment))
			.individualByName(Map.of("build_judge", judgment))
			.weights(Map.of("build_judge", 1.0))
			.build());

		String json = mapper.writeValueAsString(original);
		RecordedVerdict restored = mapper.readValue(json, RecordedVerdict.class);

		assertThat(restored.aggregated().pass()).isTrue();
		assertThat(restored.aggregated().reasoning()).isEqualTo("All checks passed");
		assertThat(restored.individualByName()).containsKey("build_judge");
		assertThat(restored.aggregated().checks()).hasSize(1);
	}

	@Test
	void recordsAndRoundTripsCompositeFailureCode() throws Exception {
		Judgment failure = Judgment.error("tier did not return a verdict");
		Verdict live = Verdict.builder()
			.aggregated(failure)
			.compositeAttempts(List.of(new CompositeAttempt("tier-1", CompositeRelation.CASCADE_TIER,
					TierPolicy.FINAL_TIER, null, new CompositeFailure(CompositeFailureCode.JURY_EXECUTION_FAILED))))
			.build();

		RecordedVerdict restored = mapper.readValue(mapper.writeValueAsString(RecordedVerdict.from(live)),
				RecordedVerdict.class);

		assertThat(restored.compositeAttempts()).singleElement().satisfies(attempt -> {
			assertThat(attempt.name()).isEqualTo("tier-1");
			assertThat(attempt.relation()).isEqualTo("cascade_tier");
			assertThat(attempt.policy()).isEqualTo("FINAL_TIER");
			assertThat(attempt.failureCode()).isEqualTo("jury_execution_failed");
			assertThat(attempt.verdict()).isNull();
		});
	}

	@Test
	void roundTripsPath() throws Exception {
		Path original = Path.of("/tmp/experiments/run-1");

		String json = mapper.writeValueAsString(original);
		Path restored = mapper.readValue(json, Path.class);

		assertThat(restored).isEqualTo(original);
	}

	@Test
	void serializesThrowableLossily() throws Exception {
		RuntimeException error = new RuntimeException("something went wrong");

		String json = mapper.writeValueAsString(error);
		JsonNode node = mapper.readTree(json);

		assertThat(node.get("className").asText()).isEqualTo("java.lang.RuntimeException");
		assertThat(node.get("message").asText()).isEqualTo("something went wrong");
	}

	@Test
	void ignoresUnknownProperties() throws Exception {
		String json = """
				{
				  "experimentId": "abc-123",
				  "experimentName": "test",
				  "datasetVersion": null,
				  "datasetDirty": false,
				  "datasetSemanticVersion": "1.0.0",
				  "knowledgeManifest": null,
				  "timestamp": "2026-01-15T10:00:00Z",
				  "items": [],
				  "metadata": {},
				  "aggregateScores": {},
				  "passRate": 0.0,
				  "totalCostUsd": 0.0,
				  "totalTokens": 0,
				  "totalDurationMs": 0,
				  "futureField": "should be ignored"
				}
				""";

		ExperimentResult result = mapper.readValue(json, ExperimentResult.class);

		assertThat(result.experimentId()).isEqualTo("abc-123");
	}

	@Test
	void handlesNullableFieldsInExperimentResult() throws Exception {
		ExperimentResult original = ExperimentResult.builder()
			.experimentId("null-test")
			.experimentName("test")
			.datasetVersion(null)
			.datasetDirty(false)
			.datasetSemanticVersion("1.0.0")
			.knowledgeManifest(null)
			.timestamp(Instant.parse("2026-01-15T10:00:00Z"))
			.items(List.of())
			.metadata(Map.of())
			.aggregateScores(Map.of())
			.passRate(0.0)
			.totalCostUsd(0.0)
			.totalTokens(0)
			.totalDurationMs(0)
			.build();

		String json = mapper.writeValueAsString(original);
		ExperimentResult restored = mapper.readValue(json, ExperimentResult.class);

		assertThat(restored.datasetVersion()).isNull();
		assertThat(restored.knowledgeManifest()).isNull();
	}

	@Test
	void roundTripsItemResultWithInvocationAndVerdict() throws Exception {
		InvocationResult invocation = InvocationResult.completed(List.of(), 100, 200, 50, 0.05, 5000, "session-1",
				Map.of("model", "opus"));
		Verdict verdict = Verdict.builder()
			.aggregated(Judgment.pass("OK"))
			.individualByName(Map.of("build", Judgment.pass("compiled")))
			.build();

		ItemResult original = ItemResult.builder()
			.itemId("ITEM-001")
			.itemSlug("simple-rename")
			.success(true)
			.passed(true)
			.costUsd(0.05)
			.totalTokens(350)
			.durationMs(5000)
			.scores(Map.of("build", 1.0))
			.metrics(Map.of("input_tokens", 100, "output_tokens", 200))
			.executionDetail(invocation)
			.verdict(verdict)
			.metadata(Map.of())
			.build();

		String json = mapper.writeValueAsString(original);
		ItemResult restored = mapper.readValue(json, ItemResult.class);

		assertThat(restored.itemId()).isEqualTo("ITEM-001");
		assertThat(restored.passed()).isTrue();
		assertThat(restored.executionDetail()).isNotNull();
		assertThat(restored.executionDetail()).isInstanceOf(InvocationResult.class);
		assertThat(((InvocationResult) restored.executionDetail()).totalTokens()).isEqualTo(350);
		assertThat(restored.verdict()).isNotNull();
		assertThat(restored.verdict().aggregated().pass()).isTrue();
	}

	@Test
	void roundTripsItemResultWithJudgeExecutionDetail() throws Exception {
		io.github.markpollack.judge.result.Judgment candidateJudgment = Judgment.pass("Passed");
		io.github.markpollack.experiment.judge.JudgeScorerResult scorerResult = new io.github.markpollack.experiment.judge.JudgeScorerResult(
				true, 1.0, "Expected PASS, got PASS");
		io.github.markpollack.experiment.judge.JudgeExecutionDetail judgeDetail = new io.github.markpollack.experiment.judge.JudgeExecutionDetail(
				candidateJudgment, "PASS", scorerResult);

		ItemResult original = ItemResult.builder()
			.itemId("JUDGE-001")
			.itemSlug("judge-item")
			.success(true)
			.passed(true)
			.scores(Map.of("agreement", 1.0))
			.executionDetail(judgeDetail)
			.metadata(Map.of("experimentType", "judge"))
			.build();

		String json = mapper.writeValueAsString(original);
		ItemResult restored = mapper.readValue(json, ItemResult.class);

		assertThat(restored.itemId()).isEqualTo("JUDGE-001");
		assertThat(restored.executionDetail()).isNotNull();
		assertThat(restored.executionDetail())
			.isInstanceOf(io.github.markpollack.experiment.judge.JudgeExecutionDetail.class);
		io.github.markpollack.experiment.judge.JudgeExecutionDetail restoredDetail = (io.github.markpollack.experiment.judge.JudgeExecutionDetail) restored
			.executionDetail();
		assertThat(restoredDetail.expectedLabel()).isEqualTo("PASS");
		assertThat(restoredDetail.scorerResult().match()).isTrue();
		assertThat(restoredDetail.candidateJudgment().pass()).isTrue();
	}

	@Test
	void bothExecutionDetailSubtypesDeserializeCorrectly() throws Exception {
		// Agent item with InvocationResult
		InvocationResult invocation = InvocationResult.completed(List.of(), 100, 200, 50, 0.05, 5000, "session-1",
				Map.of());
		ItemResult agentItem = ItemResult.builder()
			.itemId("AGENT-001")
			.itemSlug("agent-item")
			.success(true)
			.passed(true)
			.executionDetail(invocation)
			.build();

		// Judge item with JudgeExecutionDetail
		io.github.markpollack.experiment.judge.JudgeExecutionDetail judgeDetail = new io.github.markpollack.experiment.judge.JudgeExecutionDetail(
				Judgment.pass("ok"), "PASS",
				new io.github.markpollack.experiment.judge.JudgeScorerResult(true, 1.0, "match"));
		ItemResult judgeItem = ItemResult.builder()
			.itemId("JUDGE-001")
			.itemSlug("judge-item")
			.success(true)
			.passed(true)
			.executionDetail(judgeDetail)
			.build();

		// Round-trip both
		String agentJson = mapper.writeValueAsString(agentItem);
		String judgeJson = mapper.writeValueAsString(judgeItem);

		ItemResult restoredAgent = mapper.readValue(agentJson, ItemResult.class);
		ItemResult restoredJudge = mapper.readValue(judgeJson, ItemResult.class);

		assertThat(restoredAgent.executionDetail()).isInstanceOf(InvocationResult.class);
		assertThat(restoredJudge.executionDetail())
			.isInstanceOf(io.github.markpollack.experiment.judge.JudgeExecutionDetail.class);
	}

	@Test
	void roundTripsKnowledgeManifest() throws Exception {
		KnowledgeManifest original = new KnowledgeManifest(Path.of("/tmp/knowledge-store"), "abc123def", false,
				Instant.parse("2026-02-21T10:00:00Z"), List.of(new KnowledgeFileEntry("spring/boot-migration.md", 4096),
						new KnowledgeFileEntry("jakarta/servlet-api.md", 2048)),
				List.of("*.bak", "drafts/*"));

		String json = mapper.writeValueAsString(original);
		KnowledgeManifest restored = mapper.readValue(json, KnowledgeManifest.class);

		assertThat(restored.rootDir()).isEqualTo(Path.of("/tmp/knowledge-store"));
		assertThat(restored.repoCommit()).isEqualTo("abc123def");
		assertThat(restored.dirty()).isFalse();
		assertThat(restored.snapshotTimestamp()).isEqualTo(Instant.parse("2026-02-21T10:00:00Z"));
		assertThat(restored.files()).hasSize(2);
		assertThat(restored.files().get(0).relativePath()).isEqualTo("spring/boot-migration.md");
		assertThat(restored.files().get(0).sizeBytes()).isEqualTo(4096);
		assertThat(restored.files().get(1).relativePath()).isEqualTo("jakarta/servlet-api.md");
		assertThat(restored.files().get(1).sizeBytes()).isEqualTo(2048);
		assertThat(restored.exclusions()).containsExactly("*.bak", "drafts/*");
	}

	@Test
	void roundTripsKnowledgeManifestWithNullRepoCommit() throws Exception {
		KnowledgeManifest original = new KnowledgeManifest(Path.of("/tmp/kb"), null, false,
				Instant.parse("2026-02-21T10:00:00Z"), List.of(), List.of());

		String json = mapper.writeValueAsString(original);
		KnowledgeManifest restored = mapper.readValue(json, KnowledgeManifest.class);

		assertThat(restored.repoCommit()).isNull();
		assertThat(restored.files()).isEmpty();
		assertThat(restored.exclusions()).isEmpty();
	}

	private static ExperimentResult minimalResult() {
		return ExperimentResult.builder()
			.experimentId("exp-001")
			.experimentName("test-experiment")
			.datasetVersion("abc123")
			.datasetDirty(false)
			.datasetSemanticVersion("1.0.0")
			.timestamp(Instant.parse("2026-01-15T10:00:00Z"))
			.items(List.of(ItemResult.builder()
				.itemId("ITEM-001")
				.itemSlug("simple-rename")
				.success(true)
				.passed(true)
				.costUsd(0.05)
				.totalTokens(350)
				.durationMs(5000)
				.scores(Map.of("build", 1.0))
				.metrics(Map.of())
				.metadata(Map.of())
				.build()))
			.metadata(Map.of("model", "opus"))
			.aggregateScores(Map.of("build", 1.0))
			.passRate(1.0)
			.totalCostUsd(0.05)
			.totalTokens(350)
			.totalDurationMs(5000)
			.build();
	}

}
