package io.github.markpollack.experiment.dataset;

import java.nio.file.Path;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * A single dataset item loaded from disk, combining metadata from {@code item.json} with
 * computed fields. This is the full representation used during experiment execution.
 *
 * @param id stable item ID (e.g., "ORM-004")
 * @param slug human-readable slug
 * @param developerTask natural language task description for prompt construction
 * @param taskType task type identifier (e.g., "spring-boot-3-upgrade", "rename-field")
 * @param bucket difficulty bucket (e.g., "A", "B")
 * @param noChange whether the correct answer is no change
 * @param knowledgeRefs knowledge store references (relative paths from dataset root)
 * @param tags filterable tags
 * @param status item status: "active", "disabled", "deprecated"
 * @param itemDir absolute path to item directory
 * @param beforeRef git repo+commit for before state (null = use physical {@code before/}
 * dir)
 * @param referenceRef git repo+commit for human's reference implementation (null = use
 * physical {@code reference/} dir)
 */
public record DatasetItem(String id, String slug, String developerTask, String taskType, String bucket,
		boolean noChange, List<String> knowledgeRefs, List<String> tags, String status, Path itemDir,
		@Nullable SourceRef beforeRef, @Nullable SourceRef referenceRef) {

	public DatasetItem {
		java.util.Objects.requireNonNull(id, "id must not be null");
		java.util.Objects.requireNonNull(slug, "slug must not be null");
		java.util.Objects.requireNonNull(developerTask, "developerTask must not be null");
		java.util.Objects.requireNonNull(taskType, "taskType must not be null");
		java.util.Objects.requireNonNull(bucket, "bucket must not be null");
		java.util.Objects.requireNonNull(status, "status must not be null");
		java.util.Objects.requireNonNull(itemDir, "itemDir must not be null");
		knowledgeRefs = List.copyOf(knowledgeRefs);
		tags = List.copyOf(tags);
	}

}
