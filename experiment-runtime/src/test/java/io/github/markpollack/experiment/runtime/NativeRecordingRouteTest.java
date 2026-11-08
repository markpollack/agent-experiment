package io.github.markpollack.experiment.runtime;

import java.io.IOException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.experiment.runtime.NativeRecordingEvidence.Route;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * CLI versions are recorded, not gated: a terminal profile selects the route, and any
 * version on that route is accepted.
 */
class NativeRecordingRouteTest {

	@Test
	void claudeVersionedAndNeutralTerminalProfilesSelectTheSdkRoute() throws Exception {
		assertThat(Route.ofTerminal("client-terminal-claude-2.1.316/1")).isEqualTo(Route.CLAUDE_SDK);
		assertThat(Route.ofTerminal("client-terminal-claude-sdk/1")).isEqualTo(Route.CLAUDE_SDK);
	}

	@Test
	void codexVersionedAndNeutralTerminalProfilesSelectTheExecRoute() throws Exception {
		assertThat(Route.ofTerminal("client-terminal-codex-0.166.0/1")).isEqualTo(Route.CODEX_EXEC);
		assertThat(Route.ofTerminal("client-terminal-codex-exec/1")).isEqualTo(Route.CODEX_EXEC);
	}

	@Test
	void anyCliVersionOnTheRouteIsAccepted() throws Exception {
		Route.CLAUDE_SDK.requireProfileId("claude-code/2.1.316/sdk-stdio+session-file");
		Route.CLAUDE_SDK.requireProfileId("claude-code/2.1.319/sdk-stdio+session-file");
		Route.CODEX_EXEC.requireProfileId("codex-cli/0.166.0/exec-stdio+rollout");
		Route.CODEX_EXEC.requireProfileId("codex-cli/0.170.0/exec-stdio+rollout");
	}

	@Test
	void wrongRouteIsRefused() {
		assertThatThrownBy(() -> Route.CLAUDE_SDK.requireProfileId("claude-code/2.1.319/print-stdout"))
			.isInstanceOf(IOException.class)
			.hasMessageContaining("not on the CLAUDE_SDK route");
		assertThatThrownBy(() -> Route.CLAUDE_SDK.requireProfileId("codex-cli/0.166.0/exec-stdio+rollout"))
			.isInstanceOf(IOException.class);
		assertThatThrownBy(() -> Route.CODEX_EXEC.requireProfileId("claude-code/2.1.316/sdk-stdio+session-file"))
			.isInstanceOf(IOException.class);
		assertThatThrownBy(() -> Route.CLAUDE_SDK.requireProfileId("claude-code//sdk-stdio+session-file"))
			.isInstanceOf(IOException.class);
	}

	@Test
	void unknownTerminalProfileIsRefused() {
		assertThatThrownBy(() -> Route.ofTerminal("client-terminal-unknown/1")).isInstanceOf(IOException.class)
			.hasMessageContaining("Unsupported terminal native profile");
		assertThatThrownBy(() -> Route.ofTerminal("client-terminal-claude-retained-2.1.316/1"))
			.isInstanceOf(IOException.class);
	}

	@Test
	void codexProcessSettlementAppliesToAnyCodexVersion() throws Exception {
		var manifest = new ObjectMapper().readTree("""
				{"profile_id":"codex-cli/0.170.0/exec-stdio+rollout",
				 "process":{"sealed":true,"exit_code":0,"observed_resources_terminated":true}}
				""");
		assertThat(NativeRecordingEvidence.processState(manifest)).isEqualTo("sealed_exit_observed");
	}

}
