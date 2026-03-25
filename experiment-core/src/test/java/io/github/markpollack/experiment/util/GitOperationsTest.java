package io.github.markpollack.experiment.util;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class GitOperationsTest {

	@Test
	void resolveHeadReturnsCommitForGitRepo() {
		// This test runs inside the experiment-driver git repo
		Path repoDir = Path.of("src/test/resources/test-dataset").toAbsolutePath();

		String commit = GitOperations.resolveHead(repoDir);

		assertThat(commit).isNotNull();
		assertThat(commit).matches("[0-9a-f]{40}");
	}

	@Test
	void resolveHeadReturnsNullForNonGitDir(@TempDir Path tempDir) {
		String commit = GitOperations.resolveHead(tempDir);

		assertThat(commit).isNull();
	}

	@Test
	void isDirtyReturnsFalseForNonGitDir(@TempDir Path tempDir) {
		boolean dirty = GitOperations.isDirty(tempDir);

		assertThat(dirty).isFalse();
	}

	@Test
	void isDirtyReturnsBooleanForGitRepo() {
		// This test runs inside the experiment-driver git repo.
		// dirty state depends on the actual git status — we just verify it doesn't throw.
		Path repoDir = Path.of("src/test/resources/test-dataset").toAbsolutePath();

		boolean dirty = GitOperations.isDirty(repoDir);

		// Just verify it returns a boolean without error
		assertThat(dirty).isIn(true, false);
	}

}
