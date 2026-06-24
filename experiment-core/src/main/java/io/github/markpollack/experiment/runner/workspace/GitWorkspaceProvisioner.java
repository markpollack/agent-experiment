package io.github.markpollack.experiment.runner.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import io.github.markpollack.experiment.dataset.ResolvedItem;
import io.github.markpollack.experiment.dataset.SourceRef;
import io.github.markpollack.experiment.util.GitOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provisions workspaces by checking out a dataset item's
 * {@link io.github.markpollack.experiment.dataset.SourceRef} from git.
 *
 * <p>
 * For {@link WorkspaceStrategy#CLONE} (default), one persistent clone is maintained per
 * source repository under a shared cache directory and reused across items, reset to each
 * item's commit between runs. Reusing the same checkout directory keeps an external
 * IDE/LSP index warm — re-cloning per item is too slow at repo scale.
 *
 * <p>
 * For {@link WorkspaceStrategy#WORKTREE}, a fresh {@code git worktree} is added per item
 * and removed on release. This is faster but stalls IntelliJ project import, so it must
 * not be used when an IDE index tracks the workspace.
 *
 * <p>
 * Items that carry a physical {@code before/} directory instead of a git ref are
 * delegated to a {@link DefaultWorkspaceProvisioner}.
 *
 * <p>
 * Used sequentially within an experiment; not thread-safe.
 */
public final class GitWorkspaceProvisioner implements WorkspaceProvisioner {

	private static final Logger log = LoggerFactory.getLogger(GitWorkspaceProvisioner.class);

	private final WorkspaceStrategy strategy;

	private final Path cacheDir;

	private final boolean ownsCacheDir;

	private final DefaultWorkspaceProvisioner fileDelegate = new DefaultWorkspaceProvisioner();

	/** Canonical repo path -> persistent clone directory. */
	private final Map<String, Path> clonesByRepo = new HashMap<>();

	/** Returned workspace -> the teardown to run on release. */
	private final Map<Path, Runnable> releaseActions = new HashMap<>();

	private int worktreeCounter;

	/** Creates a CLONE-strategy provisioner with an auto-created temp cache directory. */
	public GitWorkspaceProvisioner() throws IOException {
		this(WorkspaceStrategy.CLONE, Files.createTempDirectory("experiment-git-cache-"), true);
	}

	/**
	 * Creates a provisioner with an explicit strategy and cache directory (not deleted on
	 * {@link #close()}).
	 * @param strategy how to materialize the checkout
	 * @param cacheDir directory under which clones/worktrees are created
	 */
	public GitWorkspaceProvisioner(WorkspaceStrategy strategy, Path cacheDir) {
		this(strategy, cacheDir, false);
	}

	private GitWorkspaceProvisioner(WorkspaceStrategy strategy, Path cacheDir, boolean ownsCacheDir) {
		this.strategy = java.util.Objects.requireNonNull(strategy, "strategy must not be null");
		this.cacheDir = java.util.Objects.requireNonNull(cacheDir, "cacheDir must not be null");
		this.ownsCacheDir = ownsCacheDir;
	}

	@Override
	public Path provision(ResolvedItem resolved) throws IOException {
		SourceRef ref = resolved.beforeRef();
		if (ref == null) {
			// No git ref — fall back to the physical before/ fixture behavior.
			Path workspace = fileDelegate.provision(resolved);
			releaseActions.put(workspace, () -> fileDelegate.release(workspace));
			return workspace;
		}
		Files.createDirectories(cacheDir);
		Path clone = ensureClone(ref.repoPath());
		return switch (strategy) {
			case CLONE -> provisionByReset(clone, ref);
			case WORKTREE -> provisionByWorktree(clone, ref);
		};
	}

	private Path provisionByReset(Path clone, SourceRef ref) {
		GitOperations.checkout(clone, ref.commitHash());
		GitOperations.resetHard(clone, ref.commitHash());
		GitOperations.clean(clone);
		Path workspace = resolveSubDir(clone, ref);
		// Reused checkout — keep it warm for the next item.
		releaseActions.put(workspace, () -> {
		});
		return workspace;
	}

	private Path provisionByWorktree(Path clone, SourceRef ref) throws IOException {
		Path worktree = cacheDir.resolve("worktree-" + (worktreeCounter++));
		DefaultWorkspaceProvisioner.deleteRecursively(worktree);
		GitOperations.addWorktree(clone, worktree, ref.commitHash());
		Path workspace = resolveSubDir(worktree, ref);
		releaseActions.put(workspace, () -> {
			GitOperations.removeWorktree(clone, worktree);
			DefaultWorkspaceProvisioner.deleteRecursively(worktree);
		});
		return workspace;
	}

	@Override
	public void release(Path workspace) {
		Runnable action = releaseActions.remove(workspace);
		if (action != null) {
			action.run();
		}
	}

	@Override
	public void close() {
		if (ownsCacheDir) {
			DefaultWorkspaceProvisioner.deleteRecursively(cacheDir);
		}
	}

	/**
	 * Clone the repo into the cache on first use; reuse the existing clone after that.
	 */
	private Path ensureClone(Path repoPath) {
		String key = repoPath.toAbsolutePath().normalize().toString();
		return clonesByRepo.computeIfAbsent(key, k -> {
			String name = repoPath.getFileName() != null ? repoPath.getFileName().toString() : "repo";
			Path target = cacheDir.resolve(name + "-" + Integer.toHexString(key.hashCode()));
			DefaultWorkspaceProvisioner.deleteRecursively(target);
			log.debug("Cloning {} into {}", repoPath, target);
			GitOperations.cloneRepo(repoPath, target);
			return target;
		});
	}

	private static Path resolveSubDir(Path root, SourceRef ref) {
		return ref.subDirectory() != null ? root.resolve(ref.subDirectory()) : root;
	}

}
