package io.github.markpollack.experiment.dataset;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatasetTest {

	@Test
	void createsValidDataset() {
		DatasetItemEntry entry = new DatasetItemEntry("SIMPLE-001", "rename-field", "items/SIMPLE-001", "A",
				"rename-field", "active");

		Dataset dataset = new Dataset("test-dataset", "Test dataset for unit testing", 1, "1.0.0",
				Path.of("/datasets/test"), Map.of("source", "test"), List.of(entry));

		assertThat(dataset.name()).isEqualTo("test-dataset");
		assertThat(dataset.description()).isEqualTo("Test dataset for unit testing");
		assertThat(dataset.schemaVersion()).isEqualTo(1);
		assertThat(dataset.declaredVersion()).isEqualTo("1.0.0");
		assertThat(dataset.rootDir()).isEqualTo(Path.of("/datasets/test"));
		assertThat(dataset.metadata()).containsEntry("source", "test");
		assertThat(dataset.itemEntries()).hasSize(1);
	}

	@Test
	void metadataIsDefensiveCopy() {
		var mutableMetadata = new java.util.HashMap<String, Object>();
		mutableMetadata.put("key", "value");

		Dataset dataset = new Dataset("test", "desc", 1, "1.0.0", Path.of("/test"), mutableMetadata, List.of());

		mutableMetadata.put("extra", "should-not-appear");
		assertThat(dataset.metadata()).doesNotContainKey("extra");
	}

	@Test
	void itemEntriesIsDefensiveCopy() {
		var mutableEntries = new java.util.ArrayList<DatasetItemEntry>();
		mutableEntries.add(new DatasetItemEntry("SIMPLE-001", "slug", "items/SIMPLE-001", "A", "taskType", "active"));

		Dataset dataset = new Dataset("test", "desc", 1, "1.0.0", Path.of("/test"), Map.of(), mutableEntries);

		mutableEntries.add(new DatasetItemEntry("EXTRA", "extra", "items/EXTRA", "B", "taskType", "active"));
		assertThat(dataset.itemEntries()).hasSize(1);
	}

	@Test
	void nullNameThrows() {
		assertThatThrownBy(() -> new Dataset(null, "desc", 1, "1.0.0", Path.of("/test"), Map.of(), List.of()))
			.isInstanceOf(NullPointerException.class)
			.hasMessageContaining("name");
	}

}
