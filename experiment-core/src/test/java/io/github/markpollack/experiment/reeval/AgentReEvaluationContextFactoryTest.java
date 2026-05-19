package io.github.markpollack.experiment.reeval;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.markpollack.experiment.agent.InvocationResult;
import io.github.markpollack.experiment.result.ItemResult;
import io.github.markpollack.judge.context.ExecutionStatus;
import io.github.markpollack.judge.context.JudgmentContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentReEvaluationContextFactoryTest {

	private final AgentReEvaluationContextFactory factory = AgentReEvaluationContextFactory.defaults();

	@Test
	void successfulReconstructionFromInvocationResult() {
		InvocationResult invocation = InvocationResult.completed(List.of(), 1000, 500, 200, 0.05, 3000, "sess-1",
				Map.of("key", "value"));

		ItemResult item = ItemResult.builder()
			.itemId("ITEM-001")
			.itemSlug("rename-field")
			.success(true)
			.passed(true)
			.costUsd(0.05)
			.totalTokens(1700)
			.durationMs(3000)
			.executionDetail(invocation)
			.workspacePath(Path.of("/tmp/workspace"))
			.build();

		Optional<JudgmentContext> result = factory.create(item);

		assertThat(result).isPresent();
		JudgmentContext context = result.get();
		assertThat(context.goal()).isEqualTo("rename-field");
		assertThat(context.workspace()).isEqualTo(Path.of("/tmp/workspace"));
		assertThat(context.status()).isEqualTo(ExecutionStatus.SUCCESS);
		assertThat(context.metadata()).containsEntry("key", "value");
	}

	@Test
	void emptyForFailedItems() {
		InvocationResult invocation = InvocationResult.error("Agent crashed", Map.of());

		ItemResult item = ItemResult.builder()
			.itemId("ITEM-002")
			.itemSlug("broken")
			.success(false)
			.passed(false)
			.executionDetail(invocation)
			.build();

		Optional<JudgmentContext> result = factory.create(item);

		assertThat(result).isEmpty();
	}

	@Test
	void emptyForNullExecutionDetail() {
		ItemResult item = ItemResult.builder()
			.itemId("ITEM-003")
			.itemSlug("no-detail")
			.success(true)
			.passed(true)
			.build();

		Optional<JudgmentContext> result = factory.create(item);

		assertThat(result).isEmpty();
	}

	@Test
	void emptyForNonInvocationResultDetail() {
		// Simulate a non-agent execution detail (e.g., future JudgeExecutionDetail)
		ItemResult item = ItemResult.builder()
			.itemId("ITEM-004")
			.itemSlug("judge-item")
			.success(true)
			.passed(true)
			.executionDetail(new io.github.markpollack.experiment.result.ExecutionDetail() {
			})
			.build();

		Optional<JudgmentContext> result = factory.create(item);

		assertThat(result).isEmpty();
	}

	@Test
	void worksWithoutWorkspacePath() {
		InvocationResult invocation = InvocationResult.completed(List.of(), 100, 50, 10, 0.01, 1000, null, Map.of());

		ItemResult item = ItemResult.builder()
			.itemId("ITEM-005")
			.itemSlug("no-workspace")
			.success(true)
			.passed(true)
			.executionDetail(invocation)
			.build();

		Optional<JudgmentContext> result = factory.create(item);

		assertThat(result).isPresent();
		assertThat(result.get().workspace()).isNull();
	}

}
