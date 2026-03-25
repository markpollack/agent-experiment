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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springaicommunity.judge.jury.Verdict;
import org.springaicommunity.judge.result.Check;
import org.springaicommunity.judge.result.Judgment;
import org.springaicommunity.judge.result.JudgmentStatus;
import org.springaicommunity.judge.score.BooleanScore;
import org.springaicommunity.judge.score.CategoricalScore;
import org.springaicommunity.judge.score.NumericalScore;
import org.springaicommunity.judge.score.Score;

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
	void roundTripsBooleanScore() throws Exception {
		Score original = new BooleanScore(true);

		String json = mapper.writeValueAsString(original);
		Score restored = mapper.readValue(json, Score.class);

		assertThat(restored).isInstanceOf(BooleanScore.class);
		assertThat(((BooleanScore) restored).value()).isTrue();
	}

	@Test
	void roundTripsNumericalScore() throws Exception {
		Score original = new NumericalScore(7.5, 0.0, 10.0);

		String json = mapper.writeValueAsString(original);
		Score restored = mapper.readValue(json, Score.class);

		assertThat(restored).isInstanceOf(NumericalScore.class);
		NumericalScore numerical = (NumericalScore) restored;
		assertThat(numerical.value()).isEqualTo(7.5);
		assertThat(numerical.min()).isEqualTo(0.0);
		assertThat(numerical.max()).isEqualTo(10.0);
	}

	@Test
	void roundTripsCategoricalScore() throws Exception {
		Score original = new CategoricalScore("good", List.of("good", "fair", "poor"));

		String json = mapper.writeValueAsString(original);
		Score restored = mapper.readValue(json, Score.class);

		assertThat(restored).isInstanceOf(CategoricalScore.class);
		CategoricalScore categorical = (CategoricalScore) restored;
		assertThat(categorical.value()).isEqualTo("good");
		assertThat(categorical.allowedValues()).containsExactly("good", "fair", "poor");
	}

	@Test
	void roundTripsVerdict() throws Exception {
		Judgment judgment = Judgment.builder()
			.score(new BooleanScore(true))
			.status(JudgmentStatus.PASS)
			.reasoning("All checks passed")
			.check(Check.pass("build", "compiled successfully"))
			.build();
		Verdict original = Verdict.builder()
			.aggregated(judgment)
			.individual(List.of(judgment))
			.individualByName(Map.of("build_judge", judgment))
			.weights(Map.of("build_judge", 1.0))
			.build();

		String json = mapper.writeValueAsString(original);
		Verdict restored = mapper.readValue(json, Verdict.class);

		assertThat(restored.aggregated().pass()).isTrue();
		assertThat(restored.aggregated().reasoning()).isEqualTo("All checks passed");
		assertThat(restored.individualByName()).containsKey("build_judge");
		assertThat(restored.aggregated().checks()).hasSize(1);
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
			.invocationResult(invocation)
			.verdict(verdict)
			.metadata(Map.of())
			.build();

		String json = mapper.writeValueAsString(original);
		ItemResult restored = mapper.readValue(json, ItemResult.class);

		assertThat(restored.itemId()).isEqualTo("ITEM-001");
		assertThat(restored.passed()).isTrue();
		assertThat(restored.invocationResult()).isNotNull();
		assertThat(restored.invocationResult().totalTokens()).isEqualTo(350);
		assertThat(restored.verdict()).isNotNull();
		assertThat(restored.verdict().aggregated().pass()).isTrue();
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
