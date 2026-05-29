package io.github.markpollack.experiment.util;

import java.nio.file.Path;
import java.util.List;

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

	@Test
	void dirtyFilesReturnsEmptyForNonGitDir(@TempDir Path tempDir) {
		List<String> files = GitOperations.dirtyFiles(tempDir);

		assertThat(files).isEmpty();
	}

	@Test
	void dirtyFilesReturnsListForGitRepo() {
		Path repoDir = Path.of("src/test/resources/test-dataset").toAbsolutePath();

		List<String> files = GitOperations.dirtyFiles(repoDir);

		// Just verify it returns a list without error
		assertThat(files).isNotNull();
	}

	@Test
	void criticalDirtyFiles_filtersToSrcAndKnowledge() {
		List<String> dirty = List.of("src/main/java/Foo.java", "knowledge/petclinic/README.md",
				"results/experiment-1/abc123.json", ".campus/status.json", "plans/inbox/idea.md", "pom.xml");

		List<String> critical = GitOperations.criticalDirtyFiles(dirty);

		assertThat(critical).containsExactly("src/main/java/Foo.java", "knowledge/petclinic/README.md");
	}

	@Test
	void criticalDirtyFiles_resultsAreNonCritical() {
		List<String> dirty = List.of("results/experiment-1/abc123.json", "results/experiment-1/index.json",
				"results/sessions/suite-1/session.json");

		List<String> critical = GitOperations.criticalDirtyFiles(dirty);

		assertThat(critical).isEmpty();
	}

	@Test
	void criticalDirtyFiles_emptyInputReturnsEmpty() {
		assertThat(GitOperations.criticalDirtyFiles(List.of())).isEmpty();
	}

	@Test
	void criticalDirtyFiles_allCriticalReturnsAll() {
		List<String> dirty = List.of("src/test/java/FooTest.java", "knowledge/base/doc.md");

		List<String> critical = GitOperations.criticalDirtyFiles(dirty);

		assertThat(critical).containsExactlyElementsOf(dirty);
	}

}
