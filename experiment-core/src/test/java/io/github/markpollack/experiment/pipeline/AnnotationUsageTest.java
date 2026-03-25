package io.github.markpollack.experiment.pipeline;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnnotationUsageTest {

	@Test
	void constructionWithClassName() {
		AnnotationUsage usage = new AnnotationUsage("org.springframework.boot.autoconfigure.SpringBootApplication",
				"PetClinicApplication.java", "PetClinicApplication");

		assertThat(usage.annotation()).isEqualTo("org.springframework.boot.autoconfigure.SpringBootApplication");
		assertThat(usage.file()).isEqualTo("PetClinicApplication.java");
		assertThat(usage.className()).isEqualTo("PetClinicApplication");
	}

	@Test
	void nullClassNameAllowed() {
		AnnotationUsage usage = new AnnotationUsage("javax.persistence.Entity", "Owner.java", null);

		assertThat(usage.className()).isNull();
	}

	@Test
	void nullAnnotationThrows() {
		assertThatThrownBy(() -> new AnnotationUsage(null, "File.java", "Foo")).isInstanceOf(NullPointerException.class)
			.hasMessageContaining("annotation");
	}

	@Test
	void nullFileThrows() {
		assertThatThrownBy(() -> new AnnotationUsage("Entity", null, "Foo")).isInstanceOf(NullPointerException.class)
			.hasMessageContaining("file");
	}

}
