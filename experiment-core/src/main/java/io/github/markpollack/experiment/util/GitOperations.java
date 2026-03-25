package io.github.markpollack.experiment.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

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
		try {
			ProcessBuilder pb = new ProcessBuilder("git", "status", "--porcelain").directory(dir.toFile())
				.redirectErrorStream(true);
			Process process = pb.start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
			int exitCode = process.waitFor();
			return exitCode == 0 && !output.isEmpty();
		}
		catch (IOException ex) {
			return false;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

}
