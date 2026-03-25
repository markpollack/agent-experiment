package io.github.markpollack.experiment.dataset;

/**
 * Index entry for a dataset item as declared in the {@code dataset.json} manifest. This
 * is the lightweight representation used for item discovery and filtering before loading
 * full item metadata from {@code item.json}.
 *
 * @param id stable item ID (e.g., "ORM-001")
 * @param slug human-readable slug
 * @param path relative path to item directory from dataset root (e.g.,
 * "items/ORM-001-add-annotation-processor")
 * @param bucket difficulty bucket (e.g., "A", "B")
 * @param taskType task type identifier
 * @param status item status: "active", "disabled", "deprecated"
 */
public record DatasetItemEntry(String id, String slug, String path, String bucket, String taskType, String status) {

	public DatasetItemEntry {
		java.util.Objects.requireNonNull(id, "id must not be null");
		java.util.Objects.requireNonNull(slug, "slug must not be null");
		java.util.Objects.requireNonNull(path, "path must not be null");
		java.util.Objects.requireNonNull(bucket, "bucket must not be null");
		java.util.Objects.requireNonNull(taskType, "taskType must not be null");
		java.util.Objects.requireNonNull(status, "status must not be null");
	}

}
