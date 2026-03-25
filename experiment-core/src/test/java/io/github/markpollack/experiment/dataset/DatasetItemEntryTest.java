package io.github.markpollack.experiment.dataset;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatasetItemEntryTest {

	@Test
	void createsValidEntry() {
		DatasetItemEntry entry = new DatasetItemEntry("ORM-001", "add-annotation-processor",
				"items/ORM-001-add-annotation-processor", "A", "org.openrewrite.maven.AddAnnotationProcessor",
				"active");

		assertThat(entry.id()).isEqualTo("ORM-001");
		assertThat(entry.slug()).isEqualTo("add-annotation-processor");
		assertThat(entry.path()).isEqualTo("items/ORM-001-add-annotation-processor");
		assertThat(entry.bucket()).isEqualTo("A");
		assertThat(entry.taskType()).isEqualTo("org.openrewrite.maven.AddAnnotationProcessor");
		assertThat(entry.status()).isEqualTo("active");
	}

	@Test
	void nullIdThrows() {
		assertThatThrownBy(() -> new DatasetItemEntry(null, "slug", "path", "A", "taskType", "active"))
			.isInstanceOf(NullPointerException.class)
			.hasMessageContaining("id");
	}

}
