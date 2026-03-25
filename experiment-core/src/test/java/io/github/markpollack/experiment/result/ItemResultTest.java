package io.github.markpollack.experiment.result;

import java.util.List;
import java.util.Map;

import io.github.markpollack.experiment.agent.InvocationResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ItemResultTest {

	@Test
	void builderCreatesValidResult() {
		InvocationResult invocation = InvocationResult.completed(List.of(), 1000, 500, 200, 0.05, 3000, "sess-1",
				Map.of());

		ItemResult result = ItemResult.builder()
			.itemId("ITEM-001")
			.itemSlug("rename-field")
			.success(true)
			.passed(true)
			.costUsd(0.05)
			.totalTokens(1700)
			.durationMs(3000)
			.scores(Map.of("build_success", 1.0, "file_comparison", 0.9))
			.metrics(Map.of("input_tokens", 1000, "output_tokens", 500))
			.invocationResult(invocation)
			.metadata(Map.of("phase_count", 3))
			.build();

		assertThat(result.itemId()).isEqualTo("ITEM-001");
		assertThat(result.itemSlug()).isEqualTo("rename-field");
		assertThat(result.success()).isTrue();
		assertThat(result.passed()).isTrue();
		assertThat(result.costUsd()).isEqualTo(0.05);
		assertThat(result.scores()).containsEntry("build_success", 1.0);
		assertThat(result.invocationResult()).isEqualTo(invocation);
		assertThat(result.verdict()).isNull();
	}

	@Test
	void nullItemIdThrows() {
		assertThatThrownBy(() -> ItemResult.builder().itemSlug("test").build()).isInstanceOf(NullPointerException.class)
			.hasMessageContaining("itemId");
	}

	@Test
	void failedItemResult() {
		ItemResult result = ItemResult.builder()
			.itemId("ITEM-002")
			.itemSlug("broken-fixture")
			.success(false)
			.passed(false)
			.costUsd(0.01)
			.totalTokens(100)
			.durationMs(500)
			.invocationResult(InvocationResult.error("Agent crashed", Map.of()))
			.build();

		assertThat(result.success()).isFalse();
		assertThat(result.passed()).isFalse();
		assertThat(result.scores()).isEmpty();
		assertThat(result.verdict()).isNull();
	}

	@Test
	void scoresMapIsDefensiveCopy() {
		var mutableScores = new java.util.HashMap<String, Double>();
		mutableScores.put("build_success", 1.0);

		ItemResult result = ItemResult.builder()
			.itemId("ITEM-001")
			.itemSlug("test")
			.success(true)
			.passed(true)
			.scores(mutableScores)
			.build();

		mutableScores.put("extra", 0.5);
		assertThat(result.scores()).doesNotContainKey("extra");
	}

}
