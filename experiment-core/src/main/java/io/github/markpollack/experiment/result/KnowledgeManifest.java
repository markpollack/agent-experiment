package io.github.markpollack.experiment.result;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import io.github.markpollack.experiment.util.GitOperations;
import org.jspecify.annotations.Nullable;

/**
 * Snapshot of the knowledge base state at experiment time. This is the independent
 * variable in ablation experiments — without it, agent improvement cannot be attributed
 * to specific knowledge changes.
 *
 * @param rootDir knowledge base root path
 * @param repoCommit git commit SHA (nullable — null if not a git repo)
 * @param dirty whether the KB directory has uncommitted changes
 * @param snapshotTimestamp when the snapshot was taken
 * @param files knowledge files present at experiment time
 * @param exclusions glob patterns of explicitly excluded files
 */
public record KnowledgeManifest(Path rootDir, @Nullable String repoCommit, boolean dirty, Instant snapshotTimestamp,
		List<KnowledgeFileEntry> files, List<String> exclusions) {

	public KnowledgeManifest {
		java.util.Objects.requireNonNull(rootDir, "rootDir must not be null");
		java.util.Objects.requireNonNull(snapshotTimestamp, "snapshotTimestamp must not be null");
		files = List.copyOf(files);
		exclusions = List.copyOf(exclusions);
	}

	/**
	 * Snapshot the knowledge base at the given root directory. Walks the directory tree
	 * and records each file's relative path and size. If the directory is a git
	 * repository, captures the current HEAD commit and dirty state.
	 * @param rootDir the knowledge base root directory
	 * @return a snapshot of the knowledge base state
	 */
	public static KnowledgeManifest snapshot(Path rootDir) {
		return snapshot(rootDir, List.of());
	}

	/**
	 * Snapshot the knowledge base with explicit exclusions.
	 * @param rootDir the knowledge base root directory
	 * @param exclusions glob patterns to exclude
	 * @return a snapshot of the knowledge base state
	 */
	public static KnowledgeManifest snapshot(Path rootDir, List<String> exclusions) {
		Instant timestamp = Instant.now();
		String repoCommit = GitOperations.resolveHead(rootDir);
		boolean dirty = GitOperations.isDirty(rootDir);

		List<KnowledgeFileEntry> entries;
		try (Stream<Path> walk = Files.walk(rootDir)) {
			entries = walk.filter(Files::isRegularFile)
				.filter(p -> !isExcluded(rootDir, p, exclusions))
				.sorted()
				.map(p -> toEntry(rootDir, p))
				.toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Failed to walk knowledge base at " + rootDir, ex);
		}

		return new KnowledgeManifest(rootDir, repoCommit, dirty, timestamp, entries, exclusions);
	}

	private static boolean isExcluded(Path rootDir, Path file, List<String> exclusions) {
		if (exclusions.isEmpty()) {
			return false;
		}
		String relative = rootDir.relativize(file).toString();
		for (String pattern : exclusions) {
			if (relative.startsWith(pattern) || matchesGlob(relative, pattern)) {
				return true;
			}
		}
		return false;
	}

	private static boolean matchesGlob(String path, String pattern) {
		// Simple prefix/suffix matching — full glob support deferred
		if (pattern.startsWith("*")) {
			return path.endsWith(pattern.substring(1));
		}
		if (pattern.endsWith("*")) {
			return path.startsWith(pattern.substring(0, pattern.length() - 1));
		}
		return path.equals(pattern);
	}

	private static KnowledgeFileEntry toEntry(Path rootDir, Path file) {
		try {
			String relativePath = rootDir.relativize(file).toString();
			long size = Files.size(file);
			return new KnowledgeFileEntry(relativePath, size);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Failed to process knowledge file: " + file, ex);
		}
	}

}
