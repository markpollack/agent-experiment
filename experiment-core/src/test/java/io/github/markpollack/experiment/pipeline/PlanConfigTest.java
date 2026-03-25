package io.github.markpollack.experiment.pipeline;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanConfigTest {

	@Test
	void constructionWithAllFields() {
		PlanConfig config = new PlanConfig(Path.of("/knowledge"),
				Map.of("javax-to-jakarta", Path.of("/tools/javax-to-jakarta.jar")), "3.0.0", "17", "sonnet",
				Duration.ofMinutes(5));

		assertThat(config.knowledgeDir()).isEqualTo(Path.of("/knowledge"));
		assertThat(config.toolPaths()).containsKey("javax-to-jakarta");
		assertThat(config.targetBootVersion()).isEqualTo("3.0.0");
		assertThat(config.targetJavaVersion()).isEqualTo("17");
		assertThat(config.model()).isEqualTo("sonnet");
		assertThat(config.timeout()).isEqualTo(Duration.ofMinutes(5));
	}

	@Test
	void nullableFields() {
		PlanConfig config = new PlanConfig(null, Map.of(), null, null, "sonnet", Duration.ofMinutes(5));

		assertThat(config.knowledgeDir()).isNull();
		assertThat(config.targetBootVersion()).isNull();
		assertThat(config.targetJavaVersion()).isNull();
	}

	@Test
	void nullModelThrows() {
		assertThatThrownBy(() -> new PlanConfig(null, Map.of(), null, null, null, Duration.ofMinutes(5)))
			.isInstanceOf(NullPointerException.class)
			.hasMessageContaining("model");
	}

	@Test
	void nullTimeoutThrows() {
		assertThatThrownBy(() -> new PlanConfig(null, Map.of(), null, null, "sonnet", null))
			.isInstanceOf(NullPointerException.class)
			.hasMessageContaining("timeout");
	}

	@Test
	void toolPathsAreImmutableCopy() {
		java.util.HashMap<String, Path> tools = new java.util.HashMap<>();
		tools.put("tool1", Path.of("/t1"));
		PlanConfig config = new PlanConfig(null, tools, null, null, "sonnet", Duration.ofMinutes(5));

		tools.put("tool2", Path.of("/t2"));
		assertThat(config.toolPaths()).hasSize(1);
	}

}
