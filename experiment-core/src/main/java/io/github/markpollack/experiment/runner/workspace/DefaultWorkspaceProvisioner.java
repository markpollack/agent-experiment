package io.github.markpollack.experiment.runner.workspace;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

import io.github.markpollack.experiment.dataset.ResolvedItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default provisioner: creates an ephemeral temp directory per item and copies the
 * physical {@code before/} fixture into it when present. Reproduces the runner's original
 * file-fixture behavior. Items carrying a git {@code beforeRef} (no physical
 * {@code before/} dir) yield an empty workspace — use {@link GitWorkspaceProvisioner} for
 * those.
 */
public final class DefaultWorkspaceProvisioner implements WorkspaceProvisioner {

	private static final Logger log = LoggerFactory.getLogger(DefaultWorkspaceProvisioner.class);

	@Override
	public Path provision(ResolvedItem resolved) throws IOException {
		Path workspace = Files.createTempDirectory("experiment-workspace-");
		if (resolved.beforeDir() != null) {
			copyDirectory(resolved.beforeDir(), workspace);
		}
		return workspace;
	}

	@Override
	public void release(Path workspace) {
		deleteRecursively(workspace);
	}

	/** Recursively copy {@code source} into {@code target}, replacing existing files. */
	static void copyDirectory(Path source, Path target) throws IOException {
		Files.walkFileTree(source, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				Files.createDirectories(target.resolve(source.relativize(dir)));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/** Recursively delete a directory tree, logging (not throwing) on failure. */
	static void deleteRecursively(Path dir) {
		if (dir == null || !Files.exists(dir)) {
			return;
		}
		try {
			Files.walkFileTree(dir, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Files.delete(file);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
					Files.delete(d);
					return FileVisitResult.CONTINUE;
				}
			});
		}
		catch (IOException ex) {
			log.warn("Failed to delete workspace {}: {}", dir, ex.getMessage());
		}
	}

}
