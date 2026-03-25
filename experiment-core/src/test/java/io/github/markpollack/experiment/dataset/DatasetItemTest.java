package io.github.markpollack.experiment.dataset;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatasetItemTest {

	@Test
	void createsValidItem() {
		DatasetItem item = new DatasetItem("ORM-004", "add-plugin", "Add the maven-compiler-plugin version 3.11.0",
				"org.openrewrite.maven.AddPlugin", "A", false, List.of("knowledge/maven/index.md"),
				List.of("multi-module", "plugin"), "active", Path.of("/datasets/test/items/ORM-004"), null, null);

		assertThat(item.id()).isEqualTo("ORM-004");
		assertThat(item.slug()).isEqualTo("add-plugin");
		assertThat(item.developerTask()).startsWith("Add the maven-compiler-plugin");
		assertThat(item.taskType()).isEqualTo("org.openrewrite.maven.AddPlugin");
		assertThat(item.bucket()).isEqualTo("A");
		assertThat(item.noChange()).isFalse();
		assertThat(item.knowledgeRefs()).containsExactly("knowledge/maven/index.md");
		assertThat(item.tags()).containsExactly("multi-module", "plugin");
		assertThat(item.status()).isEqualTo("active");
		assertThat(item.itemDir()).isEqualTo(Path.of("/datasets/test/items/ORM-004"));
	}

	@Test
	void noChangeItem() {
		DatasetItem item = new DatasetItem("SIMPLE-003", "no-change", "Verify no changes needed", "no-op", "B", true,
				List.of(), List.of("no-change"), "active", Path.of("/datasets/test/items/SIMPLE-003"), null, null);

		assertThat(item.noChange()).isTrue();
		assertThat(item.knowledgeRefs()).isEmpty();
	}

	@Test
	void knowledgeRefsIsDefensiveCopy() {
		var mutableRefs = new java.util.ArrayList<String>();
		mutableRefs.add("ref1");

		DatasetItem item = new DatasetItem("ID", "slug", "task", "taskType", "A", false, mutableRefs, List.of(),
				"active", Path.of("/test"), null, null);

		mutableRefs.add("ref2");
		assertThat(item.knowledgeRefs()).containsExactly("ref1");
	}

	@Test
	void tagsIsDefensiveCopy() {
		var mutableTags = new java.util.ArrayList<String>();
		mutableTags.add("tag1");

		DatasetItem item = new DatasetItem("ID", "slug", "task", "taskType", "A", false, List.of(), mutableTags,
				"active", Path.of("/test"), null, null);

		mutableTags.add("tag2");
		assertThat(item.tags()).containsExactly("tag1");
	}

	@Test
	void nullIdThrows() {
		assertThatThrownBy(() -> new DatasetItem(null, "slug", "task", "taskType", "A", false, List.of(), List.of(),
				"active", Path.of("/test"), null, null))
			.isInstanceOf(NullPointerException.class)
			.hasMessageContaining("id");
	}

}
