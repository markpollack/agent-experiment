package io.github.markpollack.experiment.dataset;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ItemFilterTest {

	@Test
	void allReturnsEmptyFilter() {
		ItemFilter filter = ItemFilter.all();

		assertThat(filter.bucket()).isNull();
		assertThat(filter.tags()).isNull();
		assertThat(filter.taskType()).isNull();
		assertThat(filter.noChange()).isNull();
		assertThat(filter.status()).isNull();
	}

	@Test
	void bucketFilterSetsOnlyBucket() {
		ItemFilter filter = ItemFilter.bucket("A");

		assertThat(filter.bucket()).isEqualTo("A");
		assertThat(filter.tags()).isNull();
		assertThat(filter.taskType()).isNull();
	}

	@Test
	void tagsFilterSetsTags() {
		ItemFilter filter = ItemFilter.tags(List.of("multi-module", "plugin"));

		assertThat(filter.tags()).containsExactly("multi-module", "plugin");
		assertThat(filter.bucket()).isNull();
	}

	@Test
	void taskTypeFilterSetsTaskType() {
		ItemFilter filter = ItemFilter.taskType("org.openrewrite.maven.AddPlugin");

		assertThat(filter.taskType()).isEqualTo("org.openrewrite.maven.AddPlugin");
		assertThat(filter.bucket()).isNull();
	}

	@Test
	void tagsListIsDefensiveCopy() {
		var mutableTags = new java.util.ArrayList<String>();
		mutableTags.add("tag1");

		ItemFilter filter = new ItemFilter(null, mutableTags, null, null, null);

		mutableTags.add("tag2");
		assertThat(filter.tags()).containsExactly("tag1");
	}

	@Test
	void nullBucketInFactoryThrows() {
		assertThatThrownBy(() -> ItemFilter.bucket(null)).isInstanceOf(NullPointerException.class);
	}

}
