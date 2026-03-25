package io.github.markpollack.experiment.result;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeManifestTest {

	@Test
	void snapshotWalksDirectoryAndRecordsFiles(@TempDir Path tempDir) throws IOException {
		// Create a small knowledge base
		Path recipes = tempDir.resolve("recipes");
		Files.createDirectories(recipes);
		Files.writeString(recipes.resolve("rename-field.md"), "# Rename Field\nRename all references.");
		Files.writeString(recipes.resolve("add-annotation.md"), "# Add Annotation\nAdd missing annotations.");

		Path patterns = tempDir.resolve("patterns");
		Files.createDirectories(patterns);
		Files.writeString(patterns.resolve("naming.md"), "# Naming Conventions\nUse camelCase.");

		KnowledgeManifest manifest = KnowledgeManifest.snapshot(tempDir);

		assertThat(manifest.rootDir()).isEqualTo(tempDir);
		assertThat(manifest.files()).hasSize(3);
		assertThat(manifest.snapshotTimestamp()).isNotNull();
		// Not a git repo
		assertThat(manifest.repoCommit()).isNull();
		assertThat(manifest.dirty()).isFalse();
		assertThat(manifest.exclusions()).isEmpty();
	}

	@Test
	void exclusionsFilterFiles(@TempDir Path tempDir) throws IOException {
		Files.writeString(tempDir.resolve("keep.md"), "keep this");
		Files.writeString(tempDir.resolve("remove.tmp"), "exclude this");

		KnowledgeManifest manifest = KnowledgeManifest.snapshot(tempDir, List.of("*.tmp"));

		assertThat(manifest.files()).hasSize(1);
		assertThat(manifest.files().get(0).relativePath()).isEqualTo("keep.md");
		assertThat(manifest.exclusions()).containsExactly("*.tmp");
	}

	@Test
	void fileEntriesContainCorrectMetadata(@TempDir Path tempDir) throws IOException {
		String content = "test content for size check";
		Files.writeString(tempDir.resolve("test.md"), content);

		KnowledgeManifest manifest = KnowledgeManifest.snapshot(tempDir);

		assertThat(manifest.files()).hasSize(1);
		KnowledgeFileEntry entry = manifest.files().get(0);
		assertThat(entry.relativePath()).isEqualTo("test.md");
		assertThat(entry.sizeBytes()).isEqualTo(content.getBytes().length);
	}

	@Test
	void snapshotFromTestResources() {
		Path testKb = Path.of("src/test/resources/test-knowledge-base");
		KnowledgeManifest manifest = KnowledgeManifest.snapshot(testKb);

		assertThat(manifest.files()).hasSize(2);
		// Inside the git repo, so repoCommit should be present
		assertThat(manifest.repoCommit()).isNotNull();
	}

	@Test
	void emptyDirectoryProducesEmptyManifest(@TempDir Path tempDir) {
		KnowledgeManifest manifest = KnowledgeManifest.snapshot(tempDir);

		assertThat(manifest.files()).isEmpty();
	}

	@Test
	void knowledgeFileEntryRejectsNegativeSize() {
		assertThatThrownBy(() -> new KnowledgeFileEntry("test.md", -1)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("negative");
	}

	@Test
	void snapshotCapturesGitCommitFromRepo() {
		// test-knowledge-base is inside this git repo
		Path testKb = Path.of("src/test/resources/test-knowledge-base");
		KnowledgeManifest manifest = KnowledgeManifest.snapshot(testKb);

		assertThat(manifest.repoCommit()).isNotNull();
		assertThat(manifest.repoCommit()).matches("[0-9a-f]{40}");
	}

	@Test
	void snapshotReturnsNullCommitForNonGitDir(@TempDir Path tempDir) throws IOException {
		Files.writeString(tempDir.resolve("file.md"), "content");

		KnowledgeManifest manifest = KnowledgeManifest.snapshot(tempDir);

		assertThat(manifest.repoCommit()).isNull();
		assertThat(manifest.dirty()).isFalse();
	}

}
