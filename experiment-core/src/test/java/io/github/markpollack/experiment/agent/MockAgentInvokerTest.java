package io.github.markpollack.experiment.agent;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockAgentInvokerTest {

	@Test
	void defaultHandlerReturnsSuccess() throws Exception {
		MockAgentInvoker invoker = new MockAgentInvoker();

		InvocationResult result = invoker.invoke(context("ITEM-001"));

		assertThat(result.success()).isTrue();
		assertThat(result.status()).isEqualTo(TerminalStatus.COMPLETED);
	}

	@Test
	void perItemHandlerOverridesDefault() throws Exception {
		InvocationResult errorResult = InvocationResult.error("build failed", Map.of());
		MockAgentInvoker invoker = new MockAgentInvoker().onItem("ITEM-002", errorResult);

		InvocationResult item1 = invoker.invoke(context("ITEM-001"));
		InvocationResult item2 = invoker.invoke(context("ITEM-002"));

		assertThat(item1.success()).isTrue();
		assertThat(item2.success()).isFalse();
		assertThat(item2.errorMessage()).isEqualTo("build failed");
	}

	@Test
	void defaultErrorThrowsException() {
		MockAgentInvoker invoker = new MockAgentInvoker().defaultError("agent crashed");

		assertThatThrownBy(() -> invoker.invoke(context("ITEM-001"))).isInstanceOf(AgentInvocationException.class)
			.hasMessageContaining("agent crashed");
	}

	@Test
	void recordsInvocations() throws Exception {
		MockAgentInvoker invoker = new MockAgentInvoker();

		invoker.invoke(context("ITEM-001"));
		invoker.invoke(context("ITEM-002"));
		invoker.invoke(context("ITEM-003"));

		assertThat(invoker.invocationCount()).isEqualTo(3);
		assertThat(invoker.invocations()).hasSize(3);
		assertThat(invoker.invocations().get(0).metadata()).containsEntry("itemId", "ITEM-001");
		assertThat(invoker.invocations().get(2).metadata()).containsEntry("itemId", "ITEM-003");
	}

	@Test
	void customDefaultHandler() throws Exception {
		MockAgentInvoker invoker = new MockAgentInvoker().defaultResult(ctx -> InvocationResult.completed(List.of(),
				500, 1000, 200, 0.10, 5000, "custom-session", ctx.metadata()));

		InvocationResult result = invoker.invoke(context("ITEM-001"));

		assertThat(result.inputTokens()).isEqualTo(500);
		assertThat(result.totalCostUsd()).isEqualTo(0.10);
		assertThat(result.sessionId()).isEqualTo("custom-session");
	}

	@Test
	void perItemHandlerReceivesContext() throws Exception {
		MockAgentInvoker invoker = new MockAgentInvoker().onItem("ITEM-001", ctx -> InvocationResult
			.completed(List.of(), 0, 0, 0, 0.0, ctx.timeout().toMillis(), null, ctx.metadata()));

		InvocationResult result = invoker.invoke(context("ITEM-001"));

		assertThat(result.durationMs()).isEqualTo(30000);
	}

	private static InvocationContext context(String itemId) {
		return InvocationContext.builder()
			.workspacePath(Path.of("/tmp/workspace"))
			.prompt("Do the task")
			.model("sonnet")
			.timeout(Duration.ofSeconds(30))
			.metadata(Map.of("itemId", itemId))
			.build();
	}

}
