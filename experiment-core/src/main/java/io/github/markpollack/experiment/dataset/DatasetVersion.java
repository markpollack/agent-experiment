package io.github.markpollack.experiment.dataset;

import org.jspecify.annotations.Nullable;

/**
 * Version identity for a dataset. Carries the human-managed semantic version, the git
 * commit SHA for reproducibility, and a dirty flag for uncommitted changes (DD-3).
 *
 * @param semanticVersion human-managed version from dataset.json (e.g., "1.2.0")
 * @param gitCommit git commit SHA of the dataset repository (nullable — null if not a git
 * repo)
 * @param dirty whether the dataset directory has uncommitted changes
 * @param activeItemCount number of active items (excludes disabled/deprecated)
 */
public record DatasetVersion(String semanticVersion, @Nullable String gitCommit, boolean dirty, int activeItemCount) {

	public DatasetVersion {
		java.util.Objects.requireNonNull(semanticVersion, "semanticVersion must not be null");
		if (activeItemCount < 0) {
			throw new IllegalArgumentException("activeItemCount must be non-negative");
		}
	}

}
