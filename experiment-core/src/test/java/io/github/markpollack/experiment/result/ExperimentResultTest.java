package io.github.markpollack.experiment.result;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.markpollack.experiment.agent.InvocationResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExperimentResultTest {

	@Test
	void builderCreatesValidResult() {
		ItemResult item = ItemResult.builder()
			.itemId("ITEM-001")
			.itemSlug("rename-field")
			.success(true)
			.passed(true)
			.costUsd(0.05)
			.totalTokens(1700)
			.durationMs(3000)
			.scores(Map.of("build_success", 1.0, "file_comparison", 0.9))
			.metrics(Map.of("input_tokens", 1000, "output_tokens", 500, "thinking_tokens", 200))
			.invocationResult(InvocationResult.completed(List.of(), 1000, 500, 200, 0.05, 3000, "sess-1", Map.of()))
			.build();

		ExperimentResult result = ExperimentResult.builder()
			.experimentId("exp-001")
			.experimentName("baseline-v1")
			.datasetVersion("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2")
			.datasetDirty(false)
			.datasetSemanticVersion("1.0.0")
			.timestamp(Instant.parse("2026-02-12T10:00:00Z"))
			.items(List.of(item))
			.metadata(Map.of("model", "sonnet"))
			.aggregateScores(Map.of("build_success", 1.0, "file_comparison", 0.9))
			.passRate(1.0)
			.totalCostUsd(0.05)
			.totalTokens(1700)
			.totalDurationMs(3000)
			.build();

		assertThat(result.experimentId()).isEqualTo("exp-001");
		assertThat(result.experimentName()).isEqualTo("baseline-v1");
		assertThat(result.datasetVersion()).isNotNull();
		assertThat(result.datasetDirty()).isFalse();
		assertThat(result.items()).hasSize(1);
		assertThat(result.passRate()).isEqualTo(1.0);
		assertThat(result.knowledgeManifest()).isNull();
	}

	@Test
	void builderHandlesNullDatasetVersion() {
		ExperimentResult result = ExperimentResult.builder()
			.experimentId("exp-001")
			.experimentName("test")
			.datasetVersion(null)
			.datasetDirty(false)
			.datasetSemanticVersion("1.0.0")
			.timestamp(Instant.now())
			.build();

		assertThat(result.datasetVersion()).isNull();
	}

	@Test
	void nullExperimentIdThrows() {
		assertThatThrownBy(() -> ExperimentResult.builder()
			.experimentName("test")
			.datasetVersion(null)
			.datasetDirty(false)
			.datasetSemanticVersion("1.0.0")
			.timestamp(Instant.now())
			.build()).isInstanceOf(NullPointerException.class).hasMessageContaining("experimentId");
	}

	@Test
	void itemsListIsDefensiveCopy() {
		var mutableList = new java.util.ArrayList<ItemResult>();
		mutableList.add(ItemResult.builder().itemId("ITEM-001").itemSlug("test").success(true).passed(true).build());

		ExperimentResult result = ExperimentResult.builder()
			.experimentId("exp-001")
			.experimentName("test")
			.datasetVersion("abc123")
			.datasetDirty(false)
			.datasetSemanticVersion("1.0.0")
			.timestamp(Instant.now())
			.items(mutableList)
			.build();

		mutableList.add(ItemResult.builder().itemId("EXTRA").itemSlug("extra").build());
		assertThat(result.items()).hasSize(1);
	}

}
