package io.github.markpollack.experiment.pipeline;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class NoOpAnalyzerTest {

	private final NoOpAnalyzer analyzer = new NoOpAnalyzer();

	@TempDir
	Path tempDir;

	@Test
	void returnsNonNullEnvelope() {
		AnalysisEnvelope envelope = analyzer.analyze(tempDir, AnalysisConfig.pomOnly());

		assertThat(envelope).isNotNull();
	}

	@Test
	void projectNameDerivedFromWorkspaceDirectory() {
		Path workspace = tempDir.resolve("my-consumer-project");
		workspace.toFile().mkdirs();

		AnalysisEnvelope envelope = analyzer.analyze(workspace, AnalysisConfig.pomOnly());

		assertThat(envelope.projectName()).isEqualTo("my-consumer-project");
	}

	@Test
	void buildToolIsNone() {
		AnalysisEnvelope envelope = analyzer.analyze(tempDir, AnalysisConfig.pomOnly());

		assertThat(envelope.buildTool()).isEqualTo("none");
	}

	@Test
	void springSpecificFieldsAreNullOrEmpty() {
		AnalysisEnvelope envelope = analyzer.analyze(tempDir, AnalysisConfig.pomOnly());

		assertThat(envelope.bootVersion()).isNull();
		assertThat(envelope.javaVersion()).isNull();
		assertThat(envelope.parentCoordinates()).isNull();
		assertThat(envelope.dependencies()).isEmpty();
		assertThat(envelope.importPatterns()).isEmpty();
		assertThat(envelope.annotations()).isEmpty();
		assertThat(envelope.configFiles()).isEmpty();
		assertThat(envelope.modules()).isEmpty();
	}

	@Test
	void metadataContainsAnalysisDuration() {
		AnalysisEnvelope envelope = analyzer.analyze(tempDir, AnalysisConfig.pomOnly());

		assertThat(envelope.metadata()).containsKey("analysisDurationMs");
	}

}
