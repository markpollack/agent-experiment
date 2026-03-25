package io.github.markpollack.experiment.agent.claude;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springaicommunity.claude.agent.sdk.config.PermissionMode;
import org.springaicommunity.claude.agent.sdk.transport.CLIOptions;

import io.github.markpollack.experiment.agent.InvocationContext;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeSdkInvokerTest {

	@Test
	void buildOptionsUsesResolvedModel() {
		ClaudeSdkInvokerConfig config = ClaudeSdkInvokerConfig.defaults();
		ClaudeSdkInvoker invoker = new ClaudeSdkInvoker(config);
		InvocationContext context = contextWithModel("sonnet");

		CLIOptions options = invoker.buildOptions(context);

		assertThat(options.model()).isEqualTo(CLIOptions.MODEL_SONNET);
	}

	@Test
	void buildOptionsAppliesPermissionMode() {
		ClaudeSdkInvokerConfig config = ClaudeSdkInvokerConfig.builder()
			.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
			.build();
		ClaudeSdkInvoker invoker = new ClaudeSdkInvoker(config);
		InvocationContext context = contextWithModel("haiku");

		CLIOptions options = invoker.buildOptions(context);

		assertThat(options.permissionMode()).isEqualTo(PermissionMode.BYPASS_PERMISSIONS);
	}

	@Test
	void buildOptionsAppliesBudgetAndTurns() {
		ClaudeSdkInvokerConfig config = ClaudeSdkInvokerConfig.builder().maxBudgetUsd(1.50).maxTurns(25).build();
		ClaudeSdkInvoker invoker = new ClaudeSdkInvoker(config);
		InvocationContext context = contextWithModel("sonnet");

		CLIOptions options = invoker.buildOptions(context);

		assertThat(options.maxBudgetUsd()).isEqualTo(1.50);
		assertThat(options.maxTurns()).isEqualTo(25);
	}

	@Test
	void buildOptionsAppliesThinkingTokens() {
		ClaudeSdkInvokerConfig config = ClaudeSdkInvokerConfig.builder().maxThinkingTokens(31999).build();
		ClaudeSdkInvoker invoker = new ClaudeSdkInvoker(config);
		InvocationContext context = contextWithModel("opus");

		CLIOptions options = invoker.buildOptions(context);

		assertThat(options.maxThinkingTokens()).isEqualTo(31999);
	}

	@Test
	void buildOptionsOmitsNullOptionalFields() {
		ClaudeSdkInvokerConfig config = ClaudeSdkInvokerConfig.defaults();
		ClaudeSdkInvoker invoker = new ClaudeSdkInvoker(config);
		InvocationContext context = contextWithModel("haiku");

		CLIOptions options = invoker.buildOptions(context);

		assertThat(options.maxBudgetUsd()).isNull();
		assertThat(options.maxTurns()).isNull();
		assertThat(options.maxThinkingTokens()).isNull();
	}

	@Test
	void buildOptionsAppliesSystemPrompt() {
		ClaudeSdkInvokerConfig config = ClaudeSdkInvokerConfig.defaults();
		ClaudeSdkInvoker invoker = new ClaudeSdkInvoker(config);
		InvocationContext context = InvocationContext.builder()
			.workspacePath(Path.of("/tmp/workspace"))
			.prompt("Do the task")
			.systemPrompt("You are a refactoring expert")
			.model("sonnet")
			.timeout(Duration.ofSeconds(60))
			.build();

		CLIOptions options = invoker.buildOptions(context);

		assertThat(options.appendSystemPrompt()).isEqualTo("You are a refactoring expert");
	}

	@Test
	void buildOptionsAppliesTimeout() {
		ClaudeSdkInvokerConfig config = ClaudeSdkInvokerConfig.defaults();
		ClaudeSdkInvoker invoker = new ClaudeSdkInvoker(config);
		InvocationContext context = InvocationContext.builder()
			.workspacePath(Path.of("/tmp/workspace"))
			.prompt("prompt")
			.model("haiku")
			.timeout(Duration.ofMinutes(5))
			.build();

		CLIOptions options = invoker.buildOptions(context);

		assertThat(options.timeout()).isEqualTo(Duration.ofMinutes(5));
	}

	@Test
	void resolveModelIdShortNames() {
		assertThat(ClaudeSdkInvoker.resolveModelId("sonnet")).isEqualTo(CLIOptions.MODEL_SONNET);
		assertThat(ClaudeSdkInvoker.resolveModelId("haiku")).isEqualTo(CLIOptions.MODEL_HAIKU);
		assertThat(ClaudeSdkInvoker.resolveModelId("opus")).isEqualTo(CLIOptions.MODEL_OPUS);
	}

	@Test
	void resolveModelIdPassthroughFullIds() {
		String fullId = "claude-sonnet-4-5-20250929";
		assertThat(ClaudeSdkInvoker.resolveModelId(fullId)).isEqualTo(fullId);
	}

	@Test
	void resolveModelIdCaseInsensitive() {
		assertThat(ClaudeSdkInvoker.resolveModelId("SONNET")).isEqualTo(CLIOptions.MODEL_SONNET);
		assertThat(ClaudeSdkInvoker.resolveModelId("Haiku")).isEqualTo(CLIOptions.MODEL_HAIKU);
	}

	@Test
	void configDefaultsHasExpectedValues() {
		ClaudeSdkInvokerConfig config = ClaudeSdkInvokerConfig.defaults();

		assertThat(config.permissionMode()).isEqualTo(PermissionMode.DANGEROUSLY_SKIP_PERMISSIONS);
		assertThat(config.maxBudgetUsd()).isNull();
		assertThat(config.maxTurns()).isNull();
		assertThat(config.maxThinkingTokens()).isNull();
	}

	@Test
	void configBuilderRoundTrips() {
		ClaudeSdkInvokerConfig config = ClaudeSdkInvokerConfig.builder()
			.permissionMode(PermissionMode.ACCEPT_EDITS)
			.maxBudgetUsd(2.0)
			.maxTurns(50)
			.maxThinkingTokens(16000)
			.build();

		assertThat(config.permissionMode()).isEqualTo(PermissionMode.ACCEPT_EDITS);
		assertThat(config.maxBudgetUsd()).isEqualTo(2.0);
		assertThat(config.maxTurns()).isEqualTo(50);
		assertThat(config.maxThinkingTokens()).isEqualTo(16000);
	}

	private static InvocationContext contextWithModel(String model) {
		return InvocationContext.builder()
			.workspacePath(Path.of("/tmp/workspace"))
			.prompt("Test prompt")
			.model(model)
			.timeout(Duration.ofSeconds(60))
			.build();
	}

}
