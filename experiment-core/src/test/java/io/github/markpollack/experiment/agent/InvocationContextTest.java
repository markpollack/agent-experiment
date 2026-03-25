package io.github.markpollack.experiment.agent;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvocationContextTest {

	@Test
	void builderCreatesValidContext() {
		InvocationContext ctx = InvocationContext.builder()
			.workspacePath(Path.of("/tmp/workspace"))
			.prompt("Refactor the code")
			.systemPrompt("You are a refactoring agent")
			.model("sonnet")
			.timeout(Duration.ofMinutes(5))
			.metadata(Map.of("experimentId", "exp-1", "itemId", "ITEM-001"))
			.build();

		assertThat(ctx.workspacePath()).isEqualTo(Path.of("/tmp/workspace"));
		assertThat(ctx.prompt()).isEqualTo("Refactor the code");
		assertThat(ctx.systemPrompt()).isEqualTo("You are a refactoring agent");
		assertThat(ctx.model()).isEqualTo("sonnet");
		assertThat(ctx.timeout()).isEqualTo(Duration.ofMinutes(5));
		assertThat(ctx.metadata()).containsEntry("experimentId", "exp-1");
	}

	@Test
	void nullSystemPromptIsAllowed() {
		InvocationContext ctx = InvocationContext.builder()
			.workspacePath(Path.of("/tmp"))
			.prompt("test")
			.model("sonnet")
			.timeout(Duration.ofMinutes(1))
			.build();

		assertThat(ctx.systemPrompt()).isNull();
	}

	@Test
	void metadataIsDefensiveCopy() {
		var mutableMap = new java.util.HashMap<String, String>();
		mutableMap.put("key", "value");

		InvocationContext ctx = InvocationContext.builder()
			.workspacePath(Path.of("/tmp"))
			.prompt("test")
			.model("sonnet")
			.timeout(Duration.ofMinutes(1))
			.metadata(mutableMap)
			.build();

		mutableMap.put("extra", "should-not-appear");
		assertThat(ctx.metadata()).doesNotContainKey("extra");
	}

	@Test
	void nullWorkspacePathThrows() {
		assertThatThrownBy(
				() -> InvocationContext.builder().prompt("test").model("sonnet").timeout(Duration.ofMinutes(1)).build())
			.isInstanceOf(NullPointerException.class)
			.hasMessageContaining("workspacePath");
	}

}
