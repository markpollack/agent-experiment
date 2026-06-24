package io.github.markpollack.experiment.runner.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.github.markpollack.experiment.dataset.DatasetItem;
import io.github.markpollack.experiment.dataset.ResolvedItem;
import io.github.markpollack.experiment.dataset.SourceRef;
import io.github.markpollack.experiment.util.GitOperations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link GitWorkspaceProvisioner} against a throwaway local git repository.
 * Shells out to git, mirroring the existing {@code GitOperationsTest} precedent (fast on
 * tiny local repos, so not tagged {@code integration}).
 */
class GitWorkspaceProvisionerTest {

	@Test
	void cloneStrategyChecksOutCommitContents(@TempDir Path tempDir) throws IOException {
		Path repo = initRepo(tempDir.resolve("repo"));
		writeAndCommit(repo, "file.txt", "v1");
		String sha = GitOperations.resolveHead(repo);

		try (GitWorkspaceProvisioner provisioner = new GitWorkspaceProvisioner(WorkspaceStrategy.CLONE,
				tempDir.resolve("cache"))) {
			Path workspace = provisioner.provision(resolvedWithRef(SourceRef.of(repo, sha)));

			assertThat(workspace.resolve("file.txt")).exists().hasContent("v1");
			provisioner.release(workspace);
		}
	}

	@Test
	void cloneStrategyResetsDirtyStateBetweenItems(@TempDir Path tempDir) throws IOException {
		Path repo = initRepo(tempDir.resolve("repo"));
		writeAndCommit(repo, "file.txt", "base");
		String sha = GitOperations.resolveHead(repo);

		try (GitWorkspaceProvisioner provisioner = new GitWorkspaceProvisioner(WorkspaceStrategy.CLONE,
				tempDir.resolve("cache"))) {
			Path first = provisioner.provision(resolvedWithRef(SourceRef.of(repo, sha)));
			// Dirty the warm checkout: modify a tracked file and drop an untracked one.
			Files.writeString(first.resolve("file.txt"), "tampered");
			Files.writeString(first.resolve("untracked.txt"), "junk");
			provisioner.release(first);

			Path second = provisioner.provision(resolvedWithRef(SourceRef.of(repo, sha)));

			assertThat(second).isEqualTo(first); // same warm clone reused
			assertThat(second.resolve("file.txt")).hasContent("base"); // tracked file
																		// restored
			assertThat(second.resolve("untracked.txt")).doesNotExist(); // untracked
																		// cleaned
		}
	}

	@Test
	void resolvesSubDirectoryWithinCheckout(@TempDir Path tempDir) throws IOException {
		Path repo = initRepo(tempDir.resolve("repo"));
		Files.createDirectories(repo.resolve("module-a"));
		writeAndCommit(repo, "module-a/pom.xml", "<project/>");
		String sha = GitOperations.resolveHead(repo);

		try (GitWorkspaceProvisioner provisioner = new GitWorkspaceProvisioner(WorkspaceStrategy.CLONE,
				tempDir.resolve("cache"))) {
			Path workspace = provisioner.provision(resolvedWithRef(SourceRef.of(repo, sha, "module-a")));

			assertThat(workspace.getFileName()).hasToString("module-a");
			assertThat(workspace.resolve("pom.xml")).exists().hasContent("<project/>");
			provisioner.release(workspace);
		}
	}

	@Test
	void worktreeStrategyProvisionsAndReleaseRemovesWorktree(@TempDir Path tempDir) throws IOException {
		Path repo = initRepo(tempDir.resolve("repo"));
		writeAndCommit(repo, "file.txt", "v1");
		String sha = GitOperations.resolveHead(repo);

		try (GitWorkspaceProvisioner provisioner = new GitWorkspaceProvisioner(WorkspaceStrategy.WORKTREE,
				tempDir.resolve("cache"))) {
			Path workspace = provisioner.provision(resolvedWithRef(SourceRef.of(repo, sha)));

			assertThat(workspace.resolve("file.txt")).exists().hasContent("v1");

			provisioner.release(workspace);
			assertThat(workspace).doesNotExist(); // worktree removed on release
		}
	}

	@Test
	void fallsBackToBeforeDirWhenNoGitRef(@TempDir Path tempDir) throws IOException {
		Path beforeDir = Files.createDirectories(tempDir.resolve("before"));
		Files.writeString(beforeDir.resolve("seed.txt"), "fixture");

		try (GitWorkspaceProvisioner provisioner = new GitWorkspaceProvisioner(WorkspaceStrategy.CLONE,
				tempDir.resolve("cache"))) {
			Path workspace = provisioner.provision(resolvedWithBeforeDir(beforeDir));

			assertThat(workspace.resolve("seed.txt")).exists().hasContent("fixture");
			assertThat(workspace).isNotEqualTo(beforeDir); // copied into an ephemeral dir

			provisioner.release(workspace);
			assertThat(workspace).doesNotExist(); // ephemeral fallback workspace deleted
		}
	}

	@Test
	void closeRemovesOwnedCacheButNotInjectedCache(@TempDir Path tempDir) throws IOException {
		Path repo = initRepo(tempDir.resolve("repo"));
		writeAndCommit(repo, "file.txt", "v1");
		String sha = GitOperations.resolveHead(repo);

		Path injectedCache = tempDir.resolve("injected-cache");
		try (GitWorkspaceProvisioner provisioner = new GitWorkspaceProvisioner(WorkspaceStrategy.CLONE,
				injectedCache)) {
			provisioner.provision(resolvedWithRef(SourceRef.of(repo, sha)));
		}
		assertThat(injectedCache).exists(); // injected cache is the caller's to manage

		Path ownedCache;
		try (GitWorkspaceProvisioner provisioner = new GitWorkspaceProvisioner()) {
			Path workspace = provisioner.provision(resolvedWithRef(SourceRef.of(repo, sha)));
			ownedCache = workspace; // lives under the auto-created cache dir
			assertThat(workspace).exists();
		}
		assertThat(ownedCache).doesNotExist(); // owned cache deleted on close
	}

	// --- helpers ---

	private static ResolvedItem resolvedWithRef(SourceRef ref) {
		return new ResolvedItem(item(), null, null, Path.of("item.json"), ref, null);
	}

	private static ResolvedItem resolvedWithBeforeDir(Path beforeDir) {
		return new ResolvedItem(item(), beforeDir, null, Path.of("item.json"), null, null);
	}

	private static DatasetItem item() {
		return new DatasetItem("ITEM-001", "task", "Do the thing", "task", "A", false, List.of(), List.of(), "active",
				Path.of("items/ITEM-001"), null, null);
	}

	private static Path initRepo(Path dir) throws IOException {
		Files.createDirectories(dir);
		run(dir, "git", "init", "--quiet");
		run(dir, "git", "config", "user.email", "test@example.com");
		run(dir, "git", "config", "user.name", "Test");
		return dir;
	}

	private static void writeAndCommit(Path repo, String relativePath, String content) throws IOException {
		Path file = repo.resolve(relativePath);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
		run(repo, "git", "add", "-A");
		run(repo, "git", "commit", "--quiet", "-m", "commit " + relativePath);
	}

	private static void run(Path dir, String... command) {
		try {
			Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			int exit = process.waitFor();
			if (exit != 0) {
				throw new IllegalStateException("Command failed: " + String.join(" ", command) + "\n" + output);
			}
		}
		catch (IOException ex) {
			throw new RuntimeException(ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(ex);
		}
	}

}
