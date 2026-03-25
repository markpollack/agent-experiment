package io.github.markpollack.experiment.dataset;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResolvedItemTest {

	@Test
	void createsValidResolvedItem() {
		DatasetItem item = new DatasetItem("SIMPLE-001", "rename-field", "Rename name to fullName", "rename-field", "A",
				false, List.of(), List.of("rename"), "active", Path.of("/datasets/test/items/SIMPLE-001"), null, null);

		ResolvedItem resolved = new ResolvedItem(item, Path.of("/datasets/test/items/SIMPLE-001/before"),
				Path.of("/datasets/test/items/SIMPLE-001/reference"),
				Path.of("/datasets/test/items/SIMPLE-001/item.json"), null, null);

		assertThat(resolved.item()).isEqualTo(item);
		assertThat(resolved.beforeDir()).isEqualTo(Path.of("/datasets/test/items/SIMPLE-001/before"));
		assertThat(resolved.referenceDir()).isEqualTo(Path.of("/datasets/test/items/SIMPLE-001/reference"));
		assertThat(resolved.itemJsonPath()).isEqualTo(Path.of("/datasets/test/items/SIMPLE-001/item.json"));
		assertThat(resolved.beforeRef()).isNull();
		assertThat(resolved.referenceRef()).isNull();
	}

	@Test
	void nullItemThrows() {
		assertThatThrownBy(() -> new ResolvedItem(null, Path.of("/before"), Path.of("/reference"),
				Path.of("/item.json"), null, null))
			.isInstanceOf(NullPointerException.class)
			.hasMessageContaining("item");
	}

	@Test
	void nullBeforeDirAllowedWhenRefPresent() {
		DatasetItem item = new DatasetItem("ID", "slug", "task", "taskType", "A", false, List.of(), List.of(), "active",
				Path.of("/test"), SourceRef.of(Path.of("/repo"), "abc123"), null);

		ResolvedItem resolved = new ResolvedItem(item, null, Path.of("/test/reference"), Path.of("/test/item.json"),
				item.beforeRef(), null);

		assertThat(resolved.beforeDir()).isNull();
		assertThat(resolved.beforeRef()).isNotNull();
		assertThat(resolved.referenceDir()).isNotNull();
	}

	@Test
	void nullReferenceDirAllowedWhenRefPresent() {
		DatasetItem item = new DatasetItem("ID", "slug", "task", "taskType", "A", false, List.of(), List.of(), "active",
				Path.of("/test"), null, SourceRef.of(Path.of("/repo"), "def456"));

		ResolvedItem resolved = new ResolvedItem(item, Path.of("/test/before"), null, Path.of("/test/item.json"), null,
				item.referenceRef());

		assertThat(resolved.referenceDir()).isNull();
		assertThat(resolved.referenceRef()).isNotNull();
		assertThat(resolved.beforeDir()).isNotNull();
	}

}
