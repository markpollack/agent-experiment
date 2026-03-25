package io.github.markpollack.experiment.dataset;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Filter criteria for selecting dataset items. All criteria are optional — null means
 * "match all" for that dimension. Multiple non-null criteria are combined with AND
 * semantics.
 *
 * @param bucket difficulty bucket to include (e.g., "A")
 * @param tags items must have all specified tags
 * @param taskType task type identifier to match
 * @param noChange if non-null, filter by noChange flag
 * @param status item status to match (defaults to "active" in DatasetManager)
 */
public record ItemFilter(@Nullable String bucket, @Nullable List<String> tags, @Nullable String taskType,
		@Nullable Boolean noChange, @Nullable String status) {

	public ItemFilter {
		if (tags != null) {
			tags = List.copyOf(tags);
		}
	}

	/** Match all active items (no filtering). */
	public static ItemFilter all() {
		return new ItemFilter(null, null, null, null, null);
	}

	/** Match items in a specific bucket. */
	public static ItemFilter bucket(String bucket) {
		java.util.Objects.requireNonNull(bucket, "bucket must not be null");
		return new ItemFilter(bucket, null, null, null, null);
	}

	/** Match items with specific tags. */
	public static ItemFilter tags(List<String> tags) {
		java.util.Objects.requireNonNull(tags, "tags must not be null");
		return new ItemFilter(null, tags, null, null, null);
	}

	/** Match items with a specific task type. */
	public static ItemFilter taskType(String taskType) {
		java.util.Objects.requireNonNull(taskType, "taskType must not be null");
		return new ItemFilter(null, null, taskType, null, null);
	}

}
