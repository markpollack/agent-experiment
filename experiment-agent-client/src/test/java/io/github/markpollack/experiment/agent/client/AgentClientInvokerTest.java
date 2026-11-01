package io.github.markpollack.experiment.agent.client;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.github.markpollack.agents.claude.ClaudeAgentOptions;
import io.github.markpollack.agents.client.AgentClientResponse;
import io.github.markpollack.agents.model.AgentApi;
import io.github.markpollack.agents.model.AgentGeneration;
import io.github.markpollack.agents.model.AgentGenerationMetadata;
import io.github.markpollack.agents.model.AgentOptions;
import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentResponseMetadata;

import org.junit.jupiter.api.Test;

import io.github.markpollack.experiment.agent.InvocationContext;
import io.github.markpollack.experiment.agent.InvocationResult;
import io.github.markpollack.experiment.agent.TerminalStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentClientInvokerTest {

	private static InvocationContext context() {
		return InvocationContext.builder()
			.workspacePath(Path.of("/tmp/workspace"))
			.prompt("do the task")
			.model("sonnet")
			.timeout(Duration.ofMinutes(5))
			.metadata(Map.of("itemId", "item-1"))
			.build();
	}

	private static Object capture(boolean error) {
		return Map.of("isError", error, "totalCostUsd", 99);
	}

	private static AgentClientResponse response(Object phaseCapture, boolean successful) {
		AgentGenerationMetadata generationMetadata = new AgentGenerationMetadata(successful ? "SUCCESS" : "ERROR",
				Map.of());
		AgentResponseMetadata metadata = AgentResponseMetadata.builder()
			.model("sonnet")
			.duration(Duration.ofSeconds(2))
			.sessionId("session-from-metadata")
			.providerFields(phaseCapture != null ? Map.of("phaseCapture", phaseCapture) : Map.of())
			.build();
		return new AgentClientResponse(
				new AgentResponse(List.of(new AgentGeneration("all done", generationMetadata)), metadata));
	}

	@Test
	void claudeOptionsCarryTheInvocationContext() {
		AgentOptions options = AgentClientInvoker.claudeOptions(context());

		assertThat(options).isInstanceOf(ClaudeAgentOptions.class);
		assertThat(options.getModel()).isEqualTo("sonnet");
		assertThat(options.getTimeout()).isEqualTo(Duration.ofMinutes(5));
		assertThat(options.getWorkingDirectory()).isEqualTo("/tmp/workspace");
		assertThat(options.isAutoApprove()).isTrue();
	}

	@Test
	void modelNamePassesThroughUntranslated() {
		AgentOptions options = AgentClientInvoker.claudeOptions(InvocationContext.builder()
			.workspacePath(Path.of("/tmp/workspace"))
			.prompt("p")
			.model("claude-opus-4-1-20250805")
			.timeout(Duration.ofMinutes(1))
			.build());

		assertThat(options.getModel()).isEqualTo("claude-opus-4-1-20250805");
	}

	@Test
	void systemPromptIsAppendedOnlyWhenPresent() {
		ClaudeAgentOptions without = (ClaudeAgentOptions) AgentClientInvoker.claudeOptions(context());
		assertThat(without.getAppendSystemPrompt()).isNull();

		InvocationContext withSystemPrompt = InvocationContext.builder()
			.workspacePath(Path.of("/tmp/workspace"))
			.prompt("do the task")
			.systemPrompt("be terse")
			.model("sonnet")
			.timeout(Duration.ofMinutes(5))
			.build();

		ClaudeAgentOptions with = (ClaudeAgentOptions) AgentClientInvoker.claudeOptions(withSystemPrompt);
		assertThat(with.getAppendSystemPrompt()).isEqualTo("be terse");
	}

	@Test
	void legacyProviderCaptureCannotManufactureCurrentMeasurements() {
		InvocationResult result = new AgentClientInvoker().toResult(response(capture(false), true), context(),
				System.currentTimeMillis());

		assertThat(result.success()).isTrue();
		assertThat(result.status()).isEqualTo(TerminalStatus.COMPLETED);
		assertThat(result.phases()).isEmpty();
		assertThat(result.sessionId()).isEqualTo("session-from-metadata");
		assertThat(result.metadata()).containsEntry("itemId", "item-1")
			.containsEntry("measurementState", "UNKNOWN")
			.containsEntry("agentResponse", "all done");
	}

	@Test
	void legacyCaptureDoesNotReplaceTheActualClientResponse() {
		InvocationResult result = new AgentClientInvoker().toResult(response(capture(true), true), context(),
				System.currentTimeMillis());

		assertThat(result.success()).isTrue();
		assertThat(result.status()).isEqualTo(TerminalStatus.COMPLETED);
		assertThat(result.phases()).isEmpty();
	}

	@Test
	void aProviderThatPublishesNoCaptureStillYieldsAResult() {
		InvocationResult result = new AgentClientInvoker().toResult(response(null, true), context(),
				System.currentTimeMillis());

		assertThat(result.success()).isTrue();
		assertThat(result.phases()).isEmpty();
		assertThat(result.inputTokens()).isZero();
		assertThat(result.totalCostUsd()).isZero();
		assertThat(result.sessionId()).isEqualTo("session-from-metadata");
	}

	@Test
	void aFailureWithNoCaptureIsAnError() {
		InvocationResult result = new AgentClientInvoker().toResult(response(null, false), context(),
				System.currentTimeMillis());

		assertThat(result.success()).isFalse();
		assertThat(result.status()).isEqualTo(TerminalStatus.ERROR);
		assertThat(result.errorMessage()).contains("Agent reported failure");
	}

	@Test
	void anUnmodelledCaptureTypeDoesNotFailTheInvocation() {
		InvocationResult result = new AgentClientInvoker().toResult(response("some other provider's capture", true),
				context(), System.currentTimeMillis());

		assertThat(result.success()).isTrue();
		assertThat(result.phases()).isEmpty();
	}

	@Test
	void anyProviderCanBeDrivenWithoutClaude() throws Exception {
		AgentApi stub = request -> response(capture(false), true).getAgentResponse();

		InvocationResult result = new AgentClientInvoker(stub, AgentClientInvokerTest::portableOptions)
			.invoke(context());

		assertThat(result.success()).isTrue();
		assertThat(result.phases()).isEmpty();
		assertThat(result.metadata()).containsEntry("measurementState", "UNKNOWN");
	}

	@Test
	void constructorsRejectNulls() {
		assertThatThrownBy(() -> new AgentClientInvoker((AgentApi) null)).isInstanceOf(NullPointerException.class)
			.hasMessageContaining("agentApi");

		assertThatThrownBy(
				() -> new AgentClientInvoker((java.util.function.Function<InvocationContext, AgentOptions>) null))
			.isInstanceOf(NullPointerException.class)
			.hasMessageContaining("optionsFactory");
	}

	private static AgentOptions portableOptions(InvocationContext context) {
		return io.github.markpollack.agents.client.DefaultAgentOptions.builder()
			.model(context.model())
			.timeout(context.timeout())
			.workingDirectory(context.workspacePath().toString())
			.build();
	}

}
