package io.github.markpollack.experiment.dataset;

import java.nio.file.Path;

import org.jspecify.annotations.Nullable;

/**
 * Resolved paths and refs for a dataset item's fixture files. Produced by
 * {@link DatasetManager#resolve(DatasetItem)}.
 * <p>
 * For each axis (before/reference), exactly one of the physical dir or the
 * {@link SourceRef} is non-null. When a ref is present, the corresponding physical dir is
 * null (checkout happens at workspace setup time, not here).
 *
 * @param item the source dataset item
 * @param beforeDir absolute path to {@code before/} directory (null when beforeRef is
 * present)
 * @param referenceDir absolute path to {@code reference/} directory (null when
 * referenceRef is present)
 * @param itemJsonPath absolute path to {@code item.json}
 * @param beforeRef git repo+commit for before state (null when beforeDir is present)
 * @param referenceRef git repo+commit for reference (null when referenceDir is present)
 */
public record ResolvedItem(DatasetItem item, @Nullable Path beforeDir, @Nullable Path referenceDir, Path itemJsonPath,
		@Nullable SourceRef beforeRef, @Nullable SourceRef referenceRef) {

	public ResolvedItem {
		java.util.Objects.requireNonNull(item, "item must not be null");
		java.util.Objects.requireNonNull(itemJsonPath, "itemJsonPath must not be null");
	}

}
