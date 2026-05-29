package io.github.markpollack.experiment.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Shared git operations used by both {@code FileSystemDatasetManager} and
 * {@code KnowledgeManifest} for resolving repository identity.
 */
public final class GitOperations {

	private GitOperations() {
	}

	/**
	 * Resolve the current git HEAD commit SHA for the given directory. Returns null if
	 * the directory is not inside a git repository.
	 * @param dir the directory to resolve git HEAD for
	 * @return the 40-character hex commit SHA, or null if not a git repo
	 */
	public static @Nullable String resolveHead(Path dir) {
		try {
			ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "HEAD").directory(dir.toFile())
				.redirectErrorStream(true);
			Process process = pb.start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
			int exitCode = process.waitFor();
			return exitCode == 0 ? output : null;
		}
		catch (IOException ex) {
			return null;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	/**
	 * Check if the given directory has uncommitted changes. Returns false if not in a git
	 * repo (conservative — assume clean).
	 * @param dir the directory to check for dirty state
	 * @return true if the directory has uncommitted changes
	 */
	public static boolean isDirty(Path dir) {
		return !dirtyFiles(dir).isEmpty();
	}

	/**
	 * Return the list of dirty file paths (relative to repo root) from
	 * {@code git status --porcelain}. Returns an empty list if not in a git repo or if
	 * clean.
	 * @param dir the directory to check
	 * @return list of dirty file paths (never null)
	 */
	public static List<String> dirtyFiles(Path dir) {
		try {
			ProcessBuilder pb = new ProcessBuilder("git", "status", "--porcelain").directory(dir.toFile())
				.redirectErrorStream(true);
			Process process = pb.start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
			int exitCode = process.waitFor();
			if (exitCode != 0 || output.isEmpty()) {
				return List.of();
			}
			return output.lines()
				.map(line -> line.length() > 3 ? line.substring(3) : line)
				.map(String::trim)
				.filter(f -> !f.isEmpty())
				.toList();
		}
		catch (IOException ex) {
			return List.of();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return List.of();
		}
	}

	/**
	 * Directories whose changes affect experiment behavior and should block runs by
	 * default. Files outside these directories are considered non-critical
	 * (documentation, tooling artifacts, etc.).
	 */
	private static final Set<String> CRITICAL_PREFIXES = Set.of("src/", "knowledge/");

	/**
	 * Filter a list of dirty file paths to only those that could affect experiment
	 * behavior.
	 * @param dirtyFiles list of relative file paths from {@link #dirtyFiles(Path)}
	 * @return subset of files under critical directories
	 */
	public static List<String> criticalDirtyFiles(List<String> dirtyFiles) {
		return dirtyFiles.stream().filter(f -> CRITICAL_PREFIXES.stream().anyMatch(f::startsWith)).toList();
	}

}
