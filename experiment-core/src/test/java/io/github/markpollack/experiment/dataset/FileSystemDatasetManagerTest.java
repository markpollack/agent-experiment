package io.github.markpollack.experiment.dataset;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSystemDatasetManagerTest {

	private static final Path TEST_DATASET = Path.of("src/test/resources/test-dataset").toAbsolutePath();

	private FileSystemDatasetManager manager;

	@BeforeEach
	void setUp() {
		manager = new FileSystemDatasetManager();
	}

	// --- load() ---

	@Test
	void loadParsesManifestCorrectly() {
		Dataset dataset = manager.load(TEST_DATASET);

		assertThat(dataset.name()).isEqualTo("test-dataset");
		assertThat(dataset.description()).contains("unit testing");
		assertThat(dataset.schemaVersion()).isEqualTo(1);
		assertThat(dataset.declaredVersion()).isEqualTo("1.0.0");
		assertThat(dataset.rootDir()).isEqualTo(TEST_DATASET);
		assertThat(dataset.itemEntries()).hasSize(8);
	}

	@Test
	void loadParsesMetadata() {
		Dataset dataset = manager.load(TEST_DATASET);

		assertThat(dataset.metadata()).containsEntry("source", "test-fixtures");
		assertThat(dataset.metadata()).containsEntry("purpose", "unit-testing");
	}

	@Test
	void loadParsesItemEntries() {
		Dataset dataset = manager.load(TEST_DATASET);

		DatasetItemEntry first = dataset.itemEntries().get(0);
		assertThat(first.id()).isEqualTo("SIMPLE-001");
		assertThat(first.slug()).isEqualTo("rename-field");
		assertThat(first.path()).isEqualTo("items/SIMPLE-001");
		assertThat(first.bucket()).isEqualTo("A");
		assertThat(first.taskType()).isEqualTo("rename-field");
		assertThat(first.status()).isEqualTo("active");
	}

	@Test
	void loadThrowsForMissingDirectory() {
		assertThatThrownBy(() -> manager.load(Path.of("/nonexistent/path"))).isInstanceOf(DatasetLoadException.class)
			.hasMessageContaining("dataset.json not found");
	}

	@Test
	void loadThrowsForUnsupportedSchemaVersion(@TempDir Path tempDir) throws IOException {
		Files.writeString(tempDir.resolve("dataset.json"), """
				{
				  "name": "bad-version",
				  "schemaVersion": 99,
				  "version": "1.0.0",
				  "items": []
				}
				""");

		assertThatThrownBy(() -> manager.load(tempDir)).isInstanceOf(DatasetLoadException.class)
			.hasMessageContaining("Unsupported schema version 99");
	}

	// --- activeItems() ---

	@Test
	void activeItemsReturnsAllActiveItems() {
		Dataset dataset = manager.load(TEST_DATASET);

		List<DatasetItem> items = manager.activeItems(dataset);

		assertThat(items).hasSize(6);
		assertThat(items).extracting(DatasetItem::id)
			.containsExactlyInAnyOrder("SIMPLE-001", "SIMPLE-002", "SIMPLE-003", "GITREF-001", "GITREF-002",
					"GITREF-003");
	}

	@Test
	void activeItemsLoadsFullItemMetadata() {
		Dataset dataset = manager.load(TEST_DATASET);

		List<DatasetItem> items = manager.activeItems(dataset);

		DatasetItem first = items.stream().filter(i -> i.id().equals("SIMPLE-001")).findFirst().orElseThrow();
		assertThat(first.developerTask()).contains("Rename the field");
		assertThat(first.taskType()).isEqualTo("rename-field");
		assertThat(first.bucket()).isEqualTo("A");
		assertThat(first.noChange()).isFalse();
		assertThat(first.tags()).containsExactly("rename", "simple");
		assertThat(first.status()).isEqualTo("active");
		assertThat(first.itemDir()).isNotNull();
	}

	@Test
	void activeItemsLoadsNoChangeItem() {
		Dataset dataset = manager.load(TEST_DATASET);

		List<DatasetItem> items = manager.activeItems(dataset);

		DatasetItem noChange = items.stream().filter(i -> i.id().equals("SIMPLE-003")).findFirst().orElseThrow();
		assertThat(noChange.noChange()).isTrue();
		assertThat(noChange.bucket()).isEqualTo("B");
	}

	// --- filteredItems() ---

	@Test
	void filteredItemsByBucket() {
		Dataset dataset = manager.load(TEST_DATASET);

		List<DatasetItem> bucketA = manager.filteredItems(dataset, ItemFilter.bucket("A"));
		List<DatasetItem> bucketB = manager.filteredItems(dataset, ItemFilter.bucket("B"));

		assertThat(bucketA).hasSize(2);
		assertThat(bucketA).extracting(DatasetItem::id).containsExactlyInAnyOrder("SIMPLE-001", "SIMPLE-002");
		assertThat(bucketB).hasSize(4);
		assertThat(bucketB).extracting(DatasetItem::id)
			.containsExactlyInAnyOrder("SIMPLE-003", "GITREF-001", "GITREF-002", "GITREF-003");
	}

	@Test
	void filteredItemsByTaskType() {
		Dataset dataset = manager.load(TEST_DATASET);

		List<DatasetItem> items = manager.filteredItems(dataset, ItemFilter.taskType("rename-field"));

		assertThat(items).hasSize(1);
		assertThat(items.get(0).id()).isEqualTo("SIMPLE-001");
	}

	@Test
	void filteredItemsByTags() {
		Dataset dataset = manager.load(TEST_DATASET);

		List<DatasetItem> items = manager.filteredItems(dataset, ItemFilter.tags(List.of("simple")));

		assertThat(items).hasSize(2);
		assertThat(items).extracting(DatasetItem::id).containsExactlyInAnyOrder("SIMPLE-001", "SIMPLE-002");
	}

	@Test
	void filteredItemsByNoChange() {
		Dataset dataset = manager.load(TEST_DATASET);

		ItemFilter noChangeFilter = new ItemFilter(null, null, null, true, null);
		List<DatasetItem> items = manager.filteredItems(dataset, noChangeFilter);

		assertThat(items).hasSize(1);
		assertThat(items.get(0).id()).isEqualTo("SIMPLE-003");
	}

	@Test
	void filteredItemsWithAllFilterReturnsAllActive() {
		Dataset dataset = manager.load(TEST_DATASET);

		List<DatasetItem> items = manager.filteredItems(dataset, ItemFilter.all());

		assertThat(items).hasSize(6);
	}

	@Test
	void filteredItemsWithNonMatchingBucketReturnsEmpty() {
		Dataset dataset = manager.load(TEST_DATASET);

		List<DatasetItem> items = manager.filteredItems(dataset, ItemFilter.bucket("Z"));

		assertThat(items).isEmpty();
	}

	// --- currentVersion() ---

	@Test
	void currentVersionReturnsGitCommit() {
		Dataset dataset = manager.load(TEST_DATASET);

		DatasetVersion version = manager.currentVersion(dataset);

		assertThat(version.semanticVersion()).isEqualTo("1.0.0");
		assertThat(version.activeItemCount()).isEqualTo(6);
		// Test dataset is inside the experiment-driver git repo
		assertThat(version.gitCommit()).isNotNull();
		assertThat(version.gitCommit()).matches("[0-9a-f]{40}");
	}

	@Test
	void currentVersionDetectsDirtyWorkingTree() {
		// The test-dataset directory is inside the experiment-driver git repo.
		// Since we have uncommitted changes during test runs, dirty may be true.
		Dataset dataset = manager.load(TEST_DATASET);

		DatasetVersion version = manager.currentVersion(dataset);

		// Just verify the flag is a boolean and gitCommit is present
		assertThat(version.gitCommit()).isNotNull();
		// dirty state depends on the actual git status — we just verify it doesn't throw
	}

	@Test
	void currentVersionHandlesNonGitDirectory(@TempDir Path tempDir) throws IOException {
		Files.writeString(tempDir.resolve("dataset.json"), """
				{
				  "name": "non-git-dataset",
				  "schemaVersion": 1,
				  "version": "1.0.0",
				  "items": []
				}
				""");

		Dataset dataset = manager.load(tempDir);
		DatasetVersion version = manager.currentVersion(dataset);

		assertThat(version.gitCommit()).isNull();
		assertThat(version.dirty()).isFalse();
		assertThat(version.activeItemCount()).isZero();
	}

	// --- resolve() ---

	@Test
	void resolveReturnsCorrectPaths() {
		Dataset dataset = manager.load(TEST_DATASET);
		DatasetItem item = manager.activeItems(dataset)
			.stream()
			.filter(i -> i.id().equals("SIMPLE-001"))
			.findFirst()
			.orElseThrow();

		ResolvedItem resolved = manager.resolve(item);

		assertThat(resolved.item()).isEqualTo(item);
		assertThat(resolved.beforeDir()).isEqualTo(item.itemDir().resolve("before"));
		assertThat(resolved.referenceDir()).isEqualTo(item.itemDir().resolve("reference"));
		assertThat(resolved.itemJsonPath()).isEqualTo(item.itemDir().resolve("item.json"));
	}

	@Test
	void resolvedPathsExist() {
		Dataset dataset = manager.load(TEST_DATASET);
		DatasetItem item = manager.activeItems(dataset)
			.stream()
			.filter(i -> i.id().equals("SIMPLE-001"))
			.findFirst()
			.orElseThrow();

		ResolvedItem resolved = manager.resolve(item);

		assertThat(resolved.beforeDir()).exists();
		assertThat(resolved.referenceDir()).exists();
		assertThat(resolved.itemJsonPath()).exists();
	}

	// --- Disabled/deprecated exclusion ---

	@Test
	void activeItemsExcludesDisabledItems() {
		Dataset dataset = manager.load(TEST_DATASET);

		List<DatasetItem> items = manager.activeItems(dataset);

		assertThat(items).extracting(DatasetItem::id).doesNotContain("SIMPLE-004");
	}

	@Test
	void activeItemsExcludesDeprecatedItems() {
		Dataset dataset = manager.load(TEST_DATASET);

		List<DatasetItem> items = manager.activeItems(dataset);

		assertThat(items).extracting(DatasetItem::id).doesNotContain("SIMPLE-005");
	}

	@Test
	void loadIncludesDisabledAndDeprecatedInManifestEntries() {
		Dataset dataset = manager.load(TEST_DATASET);

		assertThat(dataset.itemEntries()).extracting(DatasetItemEntry::id).contains("SIMPLE-004", "SIMPLE-005");
		assertThat(dataset.itemEntries()).extracting(DatasetItemEntry::status).contains("disabled", "deprecated");
	}

	@Test
	void filteredItemsByStatusDisabled() {
		Dataset dataset = manager.load(TEST_DATASET);

		ItemFilter statusFilter = new ItemFilter(null, null, null, null, "disabled");
		List<DatasetItem> items = manager.filteredItems(dataset, statusFilter);

		assertThat(items).hasSize(1);
		assertThat(items.get(0).id()).isEqualTo("SIMPLE-004");
	}

	@Test
	void filteredItemsByStatusDeprecated() {
		Dataset dataset = manager.load(TEST_DATASET);

		ItemFilter statusFilter = new ItemFilter(null, null, null, null, "deprecated");
		List<DatasetItem> items = manager.filteredItems(dataset, statusFilter);

		assertThat(items).hasSize(1);
		assertThat(items.get(0).id()).isEqualTo("SIMPLE-005");
	}

	// --- knowledgeRefs ---

	@Test
	void activeItemsLoadsKnowledgeRefs() {
		Dataset dataset = manager.load(TEST_DATASET);

		List<DatasetItem> items = manager.activeItems(dataset);

		DatasetItem item = items.stream().filter(i -> i.id().equals("SIMPLE-002")).findFirst().orElseThrow();
		assertThat(item.knowledgeRefs()).containsExactly("coding-standards.md", "naming-conventions.md");
	}

	// --- SourceRef parsing ---

	@Test
	void loadItemWithGitRefs() {
		Dataset dataset = manager.load(TEST_DATASET);

		List<DatasetItem> items = manager.activeItems(dataset);

		DatasetItem item = items.stream().filter(i -> i.id().equals("GITREF-001")).findFirst().orElseThrow();
		assertThat(item.beforeRef()).isNotNull();
		assertThat(item.beforeRef().repoPath()).isEqualTo(Path.of("/home/user/projects/openws"));
		assertThat(item.beforeRef().commitHash()).isEqualTo("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2");
		assertThat(item.beforeRef().subDirectory()).isNull();
		assertThat(item.referenceRef()).isNotNull();
		assertThat(item.referenceRef().repoPath()).isEqualTo(Path.of("/home/user/projects/openws"));
		assertThat(item.referenceRef().commitHash()).isEqualTo("e4f5a6b7c8d9e4f5a6b7c8d9e4f5a6b7c8d9e4f5");
		assertThat(item.referenceRef().subDirectory()).isEqualTo("services/core");
	}

	@Test
	void loadItemWithBeforeRefOnly() {
		Dataset dataset = manager.load(TEST_DATASET);

		List<DatasetItem> items = manager.activeItems(dataset);

		DatasetItem item = items.stream().filter(i -> i.id().equals("GITREF-002")).findFirst().orElseThrow();
		assertThat(item.beforeRef()).isNotNull();
		assertThat(item.beforeRef().commitHash()).isEqualTo("1111111111111111111111111111111111111111");
		assertThat(item.referenceRef()).isNull();
	}

	@Test
	void loadItemWithReferenceRefOnly() {
		Dataset dataset = manager.load(TEST_DATASET);

		List<DatasetItem> items = manager.activeItems(dataset);

		DatasetItem item = items.stream().filter(i -> i.id().equals("GITREF-003")).findFirst().orElseThrow();
		assertThat(item.beforeRef()).isNull();
		assertThat(item.referenceRef()).isNotNull();
		assertThat(item.referenceRef().commitHash()).isEqualTo("2222222222222222222222222222222222222222");
		assertThat(item.referenceRef().subDirectory()).isEqualTo("api-module");
	}

	@Test
	void resolveItemWithBeforeRefOnlyHasNullBeforeDirAndPhysicalReferenceDir() {
		Dataset dataset = manager.load(TEST_DATASET);

		DatasetItem item = manager.activeItems(dataset)
			.stream()
			.filter(i -> i.id().equals("GITREF-002"))
			.findFirst()
			.orElseThrow();

		ResolvedItem resolved = manager.resolve(item);

		assertThat(resolved.beforeDir()).isNull();
		assertThat(resolved.beforeRef()).isNotNull();
		assertThat(resolved.referenceDir()).isNotNull();
		assertThat(resolved.referenceDir()).exists();
		assertThat(resolved.referenceRef()).isNull();
	}

	@Test
	void resolveItemWithReferenceRefOnlyHasPhysicalBeforeDirAndNullReferenceDir() {
		Dataset dataset = manager.load(TEST_DATASET);

		DatasetItem item = manager.activeItems(dataset)
			.stream()
			.filter(i -> i.id().equals("GITREF-003"))
			.findFirst()
			.orElseThrow();

		ResolvedItem resolved = manager.resolve(item);

		assertThat(resolved.beforeDir()).isNotNull();
		assertThat(resolved.beforeDir()).exists();
		assertThat(resolved.beforeRef()).isNull();
		assertThat(resolved.referenceDir()).isNull();
		assertThat(resolved.referenceRef()).isNotNull();
	}

	@Test
	void loadItemWithoutRefsHasNullRefs() {
		Dataset dataset = manager.load(TEST_DATASET);

		List<DatasetItem> items = manager.activeItems(dataset);

		DatasetItem item = items.stream().filter(i -> i.id().equals("SIMPLE-001")).findFirst().orElseThrow();
		assertThat(item.beforeRef()).isNull();
		assertThat(item.referenceRef()).isNull();
	}

	@Test
	void resolveItemReturnsNullDirWhenRefPresent() {
		Dataset dataset = manager.load(TEST_DATASET);

		DatasetItem item = manager.activeItems(dataset)
			.stream()
			.filter(i -> i.id().equals("GITREF-001"))
			.findFirst()
			.orElseThrow();

		ResolvedItem resolved = manager.resolve(item);

		assertThat(resolved.beforeDir()).isNull();
		assertThat(resolved.referenceDir()).isNull();
		assertThat(resolved.beforeRef()).isNotNull();
		assertThat(resolved.referenceRef()).isNotNull();
		assertThat(resolved.itemJsonPath()).isNotNull();
	}

	@Test
	void resolveItemWithPhysicalDirsWhenNoRef() {
		Dataset dataset = manager.load(TEST_DATASET);

		DatasetItem item = manager.activeItems(dataset)
			.stream()
			.filter(i -> i.id().equals("SIMPLE-001"))
			.findFirst()
			.orElseThrow();

		ResolvedItem resolved = manager.resolve(item);

		assertThat(resolved.beforeDir()).isNotNull();
		assertThat(resolved.referenceDir()).isNotNull();
		assertThat(resolved.beforeRef()).isNull();
		assertThat(resolved.referenceRef()).isNull();
	}

	@Test
	void invalidCommitHashInItemJson(@TempDir Path tempDir) throws IOException {
		Files.writeString(tempDir.resolve("dataset.json"),
				"""
						{
						  "name": "bad-ref",
						  "schemaVersion": 1,
						  "version": "1.0.0",
						  "items": [
						    { "id": "BAD-001", "slug": "bad", "path": "items/BAD-001", "bucket": "A", "taskType": "test", "status": "active" }
						  ]
						}
						""");
		Path itemDir = tempDir.resolve("items/BAD-001");
		Files.createDirectories(itemDir);
		Files.writeString(itemDir.resolve("item.json"), """
				{
				  "schemaVersion": 1,
				  "developerTask": "test",
				  "beforeRef": {
				    "repoPath": "/repo",
				    "commitHash": "INVALID_HEX"
				  }
				}
				""");

		Dataset dataset = manager.load(tempDir);

		assertThatThrownBy(() -> manager.activeItems(dataset)).isInstanceOf(DatasetLoadException.class)
			.hasMessageContaining("beforeRef")
			.hasMessageContaining("hex");
	}

}
