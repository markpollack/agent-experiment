package io.github.markpollack.experiment.runner;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import io.github.markpollack.experiment.dataset.ItemFilter;
import io.github.markpollack.experiment.diagnostic.EfficiencyConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExperimentConfigTest {

	@Test
	void builderCreatesValidConfig() {
		ExperimentConfig config = ExperimentConfig.builder()
			.experimentName("baseline-v1")
			.datasetDir(Path.of("/datasets/petclinic"))
			.model("sonnet")
			.promptTemplate("Apply the following task: ${developerTask}")
			.perItemTimeout(Duration.ofMinutes(10))
			.systemPrompt("You are a refactoring agent")
			.knowledgeBaseDir(Path.of("/knowledge"))
			.experimentTimeout(Duration.ofHours(2))
			.metadata(Map.of("run", "1"))
			.baselineId("prev-experiment-id")
			.build();

		assertThat(config.experimentName()).isEqualTo("baseline-v1");
		assertThat(config.datasetDir()).isEqualTo(Path.of("/datasets/petclinic"));
		assertThat(config.model()).isEqualTo("sonnet");
		assertThat(config.perItemTimeout()).isEqualTo(Duration.ofMinutes(10));
		assertThat(config.systemPrompt()).isEqualTo("You are a refactoring agent");
		assertThat(config.knowledgeBaseDir()).isEqualTo(Path.of("/knowledge"));
		assertThat(config.experimentTimeout()).isEqualTo(Duration.ofHours(2));
		assertThat(config.baselineId()).isEqualTo("prev-experiment-id");
	}

	@Test
	void optionalFieldsDefaultToNull() {
		ExperimentConfig config = ExperimentConfig.builder()
			.experimentName("test")
			.datasetDir(Path.of("/data"))
			.model("sonnet")
			.promptTemplate("${developerTask}")
			.perItemTimeout(Duration.ofMinutes(5))
			.build();

		assertThat(config.itemFilter()).isNull();
		assertThat(config.systemPrompt()).isNull();
		assertThat(config.knowledgeBaseDir()).isNull();
		assertThat(config.experimentTimeout()).isNull();
		assertThat(config.baselineId()).isNull();
		assertThat(config.preserveWorkspaces()).isNull();
		assertThat(config.outputDir()).isNull();
		assertThat(config.efficiencyConfig()).isNull();
		assertThat(config.metadata()).isEmpty();
	}

	@Test
	void shouldPreserveWorkspacesDefaultsTrueWhenOutputDirSet() {
		ExperimentConfig config = ExperimentConfig.builder()
			.experimentName("test")
			.datasetDir(Path.of("/data"))
			.model("sonnet")
			.promptTemplate("${task}")
			.perItemTimeout(Duration.ofMinutes(5))
			.outputDir(Path.of("/results"))
			.build();

		assertThat(config.shouldPreserveWorkspaces()).isTrue();
	}

	@Test
	void shouldPreserveWorkspacesFalseWithoutOutputDir() {
		ExperimentConfig config = ExperimentConfig.builder()
			.experimentName("test")
			.datasetDir(Path.of("/data"))
			.model("sonnet")
			.promptTemplate("${task}")
			.perItemTimeout(Duration.ofMinutes(5))
			.build();

		assertThat(config.shouldPreserveWorkspaces()).isFalse();
	}

	@Test
	void shouldPreserveWorkspacesFalseWhenExplicitlyDisabled() {
		ExperimentConfig config = ExperimentConfig.builder()
			.experimentName("test")
			.datasetDir(Path.of("/data"))
			.model("sonnet")
			.promptTemplate("${task}")
			.perItemTimeout(Duration.ofMinutes(5))
			.outputDir(Path.of("/results"))
			.preserveWorkspaces(false)
			.build();

		assertThat(config.shouldPreserveWorkspaces()).isFalse();
	}

	@Test
	void nullExperimentNameThrows() {
		assertThatThrownBy(() -> ExperimentConfig.builder()
			.datasetDir(Path.of("/data"))
			.model("sonnet")
			.promptTemplate("${task}")
			.perItemTimeout(Duration.ofMinutes(5))
			.build()).isInstanceOf(NullPointerException.class).hasMessageContaining("experimentName");
	}

	@Test
	void itemFilterCanBeSet() {
		ExperimentConfig config = ExperimentConfig.builder()
			.experimentName("test")
			.datasetDir(Path.of("/data"))
			.itemFilter(ItemFilter.bucket("A"))
			.model("sonnet")
			.promptTemplate("${task}")
			.perItemTimeout(Duration.ofMinutes(5))
			.build();

		assertThat(config.itemFilter()).isNotNull();
		assertThat(config.itemFilter().bucket()).isEqualTo("A");
	}

	@Test
	void efficiencyConfigCanBeSet() {
		EfficiencyConfig efficiency = EfficiencyConfig.defaults();
		ExperimentConfig config = ExperimentConfig.builder()
			.experimentName("test")
			.datasetDir(Path.of("/data"))
			.model("sonnet")
			.promptTemplate("${task}")
			.perItemTimeout(Duration.ofMinutes(5))
			.efficiencyConfig(efficiency)
			.build();

		assertThat(config.efficiencyConfig()).isSameAs(efficiency);
		assertThat(config.efficiencyConfig().costCeilingUsd()).isEqualTo(5.0);
	}

	@Test
	void metadataIsDefensiveCopy() {
		var mutableMap = new java.util.HashMap<String, String>();
		mutableMap.put("key", "value");

		ExperimentConfig config = ExperimentConfig.builder()
			.experimentName("test")
			.datasetDir(Path.of("/data"))
			.model("sonnet")
			.promptTemplate("${task}")
			.perItemTimeout(Duration.ofMinutes(5))
			.metadata(mutableMap)
			.build();

		mutableMap.put("extra", "should-not-appear");
		assertThat(config.metadata()).doesNotContainKey("extra");
	}

}
