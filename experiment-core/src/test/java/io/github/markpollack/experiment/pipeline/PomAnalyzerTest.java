package io.github.markpollack.experiment.pipeline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PomAnalyzerTest {

	private final PomAnalyzer analyzer = new PomAnalyzer();

	private static final Path TEST_FIXTURE = Path.of("src/test/resources/test-pipeline/simple-project");

	@Test
	void analyzeSimpleProjectPomParsing() {
		AnalysisEnvelope envelope = analyzer.analyze(TEST_FIXTURE, AnalysisConfig.pomOnly());

		assertThat(envelope.projectName()).isEqualTo("simple-project");
		assertThat(envelope.buildTool()).isEqualTo("maven");
		assertThat(envelope.bootVersion()).isEqualTo("2.7.0");
		assertThat(envelope.javaVersion()).isEqualTo("1.8");
		assertThat(envelope.parentCoordinates()).isEqualTo("org.springframework.boot:spring-boot-starter-parent:2.7.0");
	}

	@Test
	void analyzeExtractsDependencies() {
		AnalysisEnvelope envelope = analyzer.analyze(TEST_FIXTURE, AnalysisConfig.pomOnly());

		assertThat(envelope.dependencies()).containsKey("org.springframework.boot:spring-boot-starter-web");
		assertThat(envelope.dependencies()).containsKey("org.springframework.boot:spring-boot-starter-data-jpa");
		assertThat(envelope.dependencies()).containsKey("com.h2database:h2");
		// Managed deps have "managed" as version
		assertThat(envelope.dependencies().get("org.springframework.boot:spring-boot-starter-web"))
			.isEqualTo("managed");
		// Explicit version preserved
		assertThat(envelope.dependencies().get("com.h2database:h2")).isEqualTo("2.1.214");
	}

	@Test
	void analyzeScansJavaxImportPatterns() {
		AnalysisEnvelope envelope = analyzer.analyze(TEST_FIXTURE, AnalysisConfig.pomOnly());

		assertThat(envelope.importPatterns()).containsKey("javax.persistence");
		assertThat(envelope.importPatterns()).containsKey("javax.validation");
		// javax.persistence found in both App.java and MyEntity.java
		assertThat(envelope.importPatterns().get("javax.persistence")).hasSize(2);
		// javax.validation found in App.java, MyEntity.java (main), and AppTest.java
		// (test)
		assertThat(envelope.importPatterns().get("javax.validation")).hasSizeGreaterThanOrEqualTo(2);
	}

	@Test
	void analyzeDetectsAnnotations() {
		AnalysisEnvelope envelope = analyzer.analyze(TEST_FIXTURE, AnalysisConfig.pomOnly());

		List<String> annotationNames = envelope.annotations().stream().map(AnnotationUsage::annotation).toList();
		assertThat(annotationNames).contains("SpringBootApplication", "Entity", "Table");
	}

	@Test
	void analyzeFindsConfigFiles() {
		AnalysisEnvelope envelope = analyzer.analyze(TEST_FIXTURE, AnalysisConfig.pomOnly());

		assertThat(envelope.configFiles()).hasSize(1);
		assertThat(envelope.configFiles().get(0)).endsWith("application.properties");
	}

	@Test
	void analyzeSingleModuleHasEmptyModulesList() {
		AnalysisEnvelope envelope = analyzer.analyze(TEST_FIXTURE, AnalysisConfig.pomOnly());

		assertThat(envelope.modules()).isEmpty();
	}

	@Test
	void analyzeIncludesMetadataWithDuration() {
		AnalysisEnvelope envelope = analyzer.analyze(TEST_FIXTURE, AnalysisConfig.pomOnly());

		assertThat(envelope.metadata()).containsKey("analysisDurationMs");
	}

	@Test
	void missingPomReturnsMinimalEnvelope(@TempDir Path emptyDir) {
		AnalysisEnvelope envelope = analyzer.analyze(emptyDir, AnalysisConfig.pomOnly());

		assertThat(envelope.buildTool()).isEqualTo("unknown");
		assertThat(envelope.bootVersion()).isNull();
		assertThat(envelope.dependencies()).isEmpty();
		assertThat(envelope.importPatterns()).isEmpty();
	}

	@Test
	void malformedPomThrowsAnalysisException(@TempDir Path dir) throws Exception {
		Files.writeString(dir.resolve("pom.xml"), "not xml at all {{{");

		assertThatThrownBy(() -> analyzer.analyze(dir, AnalysisConfig.pomOnly())).isInstanceOf(AnalysisException.class)
			.hasMessageContaining("Failed to analyze POM");
	}

	@Test
	void extractNamespaceGroupsCorrectly() {
		// 2-segment namespace: javax.persistence
		assertThat(analyzer.extractNamespace("javax.persistence.Entity")).isEqualTo("javax.persistence");
		assertThat(analyzer.extractNamespace("javax.persistence.Column")).isEqualTo("javax.persistence");
		// 2-segment namespace: javax.validation
		assertThat(analyzer.extractNamespace("javax.validation.constraints.NotBlank")).isEqualTo("javax.validation");
		// 4-segment namespace for javax.xml.*: javax.xml.bind.annotation
		assertThat(analyzer.extractNamespace("javax.xml.bind.annotation.XmlElement"))
			.isEqualTo("javax.xml.bind.annotation");
	}

}
