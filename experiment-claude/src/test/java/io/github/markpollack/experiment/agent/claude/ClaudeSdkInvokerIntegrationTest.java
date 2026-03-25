package io.github.markpollack.experiment.agent.claude;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.markpollack.experiment.agent.InvocationContext;
import io.github.markpollack.experiment.agent.InvocationResult;
import io.github.markpollack.experiment.agent.TerminalStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link ClaudeSdkInvoker}. Requires Claude CLI installed and API
 * key configured.
 *
 * <p>
 * Run with:
 * {@code CLAUDECODE= ./mvnw test -pl experiment-claude -Dsurefire.excludedGroups= -Dgroups=integration}
 */
@Tag("integration")
class ClaudeSdkInvokerIntegrationTest {

	@Test
	void smokeTestWithHaiku(@TempDir Path workspace) throws Exception {
		ClaudeSdkInvokerConfig config = ClaudeSdkInvokerConfig.builder().maxTurns(1).maxBudgetUsd(0.05).build();
		ClaudeSdkInvoker invoker = new ClaudeSdkInvoker(config);

		InvocationContext context = InvocationContext.builder()
			.workspacePath(workspace)
			.prompt("What is 2+2? Reply with only the number.")
			.model("haiku")
			.timeout(Duration.ofSeconds(60))
			.metadata(Map.of("test", "smoke"))
			.build();

		InvocationResult result = invoker.invoke(context);

		assertThat(result.success()).isTrue();
		assertThat(result.status()).isEqualTo(TerminalStatus.COMPLETED);
		assertThat(result.totalTokens()).isGreaterThan(0);
		assertThat(result.phases()).hasSize(1);
		assertThat(result.phases().get(0).phaseName()).isEqualTo("invoke");
		assertThat(result.metadata()).containsEntry("test", "smoke");
	}

}
