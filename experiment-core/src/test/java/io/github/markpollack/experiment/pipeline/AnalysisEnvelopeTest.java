package io.github.markpollack.experiment.pipeline;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisEnvelopeTest {

	@Test
	void builderCreatesEnvelope() {
		AnalysisEnvelope envelope = AnalysisEnvelope.builder()
			.projectName("petclinic")
			.bootVersion("2.7.3")
			.javaVersion("1.8")
			.buildTool("maven")
			.parentCoordinates("org.springframework.boot:spring-boot-starter-parent:2.7.3")
			.dependencies(Map.of("spring-boot-starter-web", "2.7.3"))
			.importPatterns(Map.of("javax.persistence", List.of("Owner.java", "Pet.java")))
			.annotations(List.of(new AnnotationUsage("org.springframework.boot.autoconfigure.SpringBootApplication",
					"PetClinicApplication.java", "PetClinicApplication")))
			.configFiles(List.of("application.properties"))
			.modules(List.of())
			.metadata(Map.of("analysisDurationMs", 42))
			.build();

		assertThat(envelope.projectName()).isEqualTo("petclinic");
		assertThat(envelope.bootVersion()).isEqualTo("2.7.3");
		assertThat(envelope.javaVersion()).isEqualTo("1.8");
		assertThat(envelope.buildTool()).isEqualTo("maven");
		assertThat(envelope.parentCoordinates()).isEqualTo("org.springframework.boot:spring-boot-starter-parent:2.7.3");
		assertThat(envelope.dependencies()).containsEntry("spring-boot-starter-web", "2.7.3");
		assertThat(envelope.importPatterns()).containsKey("javax.persistence");
		assertThat(envelope.importPatterns().get("javax.persistence")).containsExactly("Owner.java", "Pet.java");
		assertThat(envelope.annotations()).hasSize(1);
		assertThat(envelope.configFiles()).containsExactly("application.properties");
		assertThat(envelope.modules()).isEmpty();
		assertThat(envelope.metadata()).containsEntry("analysisDurationMs", 42);
	}

	@Test
	void builderDefaultsToEmptyCollections() {
		AnalysisEnvelope envelope = AnalysisEnvelope.builder().projectName("test").buildTool("maven").build();

		assertThat(envelope.dependencies()).isEmpty();
		assertThat(envelope.importPatterns()).isEmpty();
		assertThat(envelope.annotations()).isEmpty();
		assertThat(envelope.configFiles()).isEmpty();
		assertThat(envelope.modules()).isEmpty();
		assertThat(envelope.metadata()).isEmpty();
	}

	@Test
	void nullProjectNameThrows() {
		assertThatThrownBy(() -> AnalysisEnvelope.builder().buildTool("maven").build())
			.isInstanceOf(NullPointerException.class)
			.hasMessageContaining("projectName");
	}

	@Test
	void nullBuildToolDefaultsToUnknown() {
		AnalysisEnvelope envelope = AnalysisEnvelope.builder().projectName("test").build();
		assertThat(envelope.buildTool()).isEqualTo("unknown");
	}

	@Test
	void collectionsAreImmutableCopies() {
		java.util.HashMap<String, String> deps = new java.util.HashMap<>();
		deps.put("dep1", "1.0");
		AnalysisEnvelope envelope = AnalysisEnvelope.builder()
			.projectName("test")
			.buildTool("maven")
			.dependencies(deps)
			.build();

		deps.put("dep2", "2.0");
		assertThat(envelope.dependencies()).hasSize(1);
	}

	@Test
	void jsonRoundTrip() throws Exception {
		AnalysisEnvelope original = AnalysisEnvelope.builder()
			.projectName("petclinic")
			.bootVersion("2.7.3")
			.javaVersion("1.8")
			.buildTool("maven")
			.dependencies(Map.of("spring-boot-starter-web", "2.7.3"))
			.importPatterns(Map.of("javax.persistence", List.of("Owner.java")))
			.annotations(List.of(new AnnotationUsage("SpringBootApplication", "App.java", "App")))
			.configFiles(List.of("application.properties"))
			.build();

		ObjectMapper mapper = new ObjectMapper();
		String json = mapper.writeValueAsString(original);
		AnalysisEnvelope deserialized = mapper.readValue(json, AnalysisEnvelope.class);

		assertThat(deserialized.projectName()).isEqualTo(original.projectName());
		assertThat(deserialized.bootVersion()).isEqualTo(original.bootVersion());
		assertThat(deserialized.buildTool()).isEqualTo(original.buildTool());
		assertThat(deserialized.dependencies()).isEqualTo(original.dependencies());
	}

	@Test
	void jsonExcludesEmptyCollections() throws Exception {
		AnalysisEnvelope envelope = AnalysisEnvelope.builder().projectName("test").buildTool("maven").build();

		ObjectMapper mapper = new ObjectMapper();
		String json = mapper.writeValueAsString(envelope);

		assertThat(json).doesNotContain("importPatterns");
		assertThat(json).doesNotContain("annotations");
		assertThat(json).doesNotContain("configFiles");
		assertThat(json).doesNotContain("modules");
		assertThat(json).doesNotContain("metadata");
	}

}
