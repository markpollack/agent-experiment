package io.github.markpollack.experiment.pipeline;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineConfigTest {

	@Test
	void builderCreatesConfig() {
		PipelineConfig config = PipelineConfig.builder()
			.knowledgeDir(Path.of("/knowledge"))
			.toolPaths(Map.of("javax-to-jakarta", Path.of("/tools/javax-to-jakarta.jar")))
			.targetBootVersion("3.0.0")
			.targetJavaVersion("17")
			.planningModel("sonnet")
			.planningTimeout(Duration.ofMinutes(3))
			.enableAnalysis(true)
			.enablePlanning(true)
			.build();

		assertThat(config.knowledgeDir()).isEqualTo(Path.of("/knowledge"));
		assertThat(config.toolPaths()).containsKey("javax-to-jakarta");
		assertThat(config.targetBootVersion()).isEqualTo("3.0.0");
		assertThat(config.targetJavaVersion()).isEqualTo("17");
		assertThat(config.planningModel()).isEqualTo("sonnet");
		assertThat(config.planningTimeout()).isEqualTo(Duration.ofMinutes(3));
		assertThat(config.enableAnalysis()).isTrue();
		assertThat(config.enablePlanning()).isTrue();
	}

	@Test
	void builderDefaults() {
		PipelineConfig config = PipelineConfig.builder().build();

		assertThat(config.knowledgeDir()).isNull();
		assertThat(config.toolPaths()).isEmpty();
		assertThat(config.targetBootVersion()).isNull();
		assertThat(config.targetJavaVersion()).isNull();
		assertThat(config.planningModel()).isNull();
		assertThat(config.planningTimeout()).isNull();
		assertThat(config.enableAnalysis()).isTrue();
		assertThat(config.enablePlanning()).isTrue();
	}

	@Test
	void effectivePlanningTimeoutUsesConfiguredValue() {
		PipelineConfig config = PipelineConfig.builder().planningTimeout(Duration.ofMinutes(10)).build();

		assertThat(config.effectivePlanningTimeout()).isEqualTo(Duration.ofMinutes(10));
	}

	@Test
	void effectivePlanningTimeoutFallsBackToFiveMinutes() {
		PipelineConfig config = PipelineConfig.builder().build();

		assertThat(config.effectivePlanningTimeout()).isEqualTo(Duration.ofMinutes(5));
	}

	@Test
	void toolPathsAreImmutableCopy() {
		java.util.HashMap<String, Path> tools = new java.util.HashMap<>();
		tools.put("tool1", Path.of("/t1"));
		PipelineConfig config = PipelineConfig.builder().toolPaths(tools).build();

		tools.put("tool2", Path.of("/t2"));
		assertThat(config.toolPaths()).hasSize(1);
	}

	@Test
	void disablePhases() {
		PipelineConfig config = PipelineConfig.builder().enableAnalysis(false).enablePlanning(false).build();

		assertThat(config.enableAnalysis()).isFalse();
		assertThat(config.enablePlanning()).isFalse();
	}

}
