package io.github.markpollack.experiment.dataset;

import java.nio.file.Path;

import org.jspecify.annotations.Nullable;

/**
 * A reference to a specific commit in a git repository. Used to identify the before-state
 * or human's reference implementation for a dataset item without requiring physical file
 * copies.
 *
 * @param repoPath absolute path to git repo on local filesystem
 * @param commitHash the full SHA-1 hash of the target commit (cf.
 * {@code KnowledgeManifest.repoCommit} which records HEAD at snapshot time)
 * @param subDirectory optional subdirectory within the repo (for monorepos)
 */
public record SourceRef(Path repoPath, String commitHash, @Nullable String subDirectory) {

	private static final java.util.regex.Pattern HEX_PATTERN = java.util.regex.Pattern.compile("[0-9a-f]+");

	public SourceRef {
		java.util.Objects.requireNonNull(repoPath, "repoPath must not be null");
		java.util.Objects.requireNonNull(commitHash, "commitHash must not be null");
		if (commitHash.isEmpty()) {
			throw new IllegalArgumentException("commitHash must not be empty");
		}
		if (!HEX_PATTERN.matcher(commitHash).matches()) {
			throw new IllegalArgumentException("commitHash must contain only hex characters: " + commitHash);
		}
	}

	/**
	 * Create a SourceRef with no subdirectory.
	 */
	public static SourceRef of(Path repoPath, String commitHash) {
		return new SourceRef(repoPath, commitHash, null);
	}

	/**
	 * Create a SourceRef with a subdirectory.
	 */
	public static SourceRef of(Path repoPath, String commitHash, String subDirectory) {
		return new SourceRef(repoPath, commitHash, subDirectory);
	}

}
