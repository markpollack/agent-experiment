package io.github.markpollack.experiment.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.markpollack.experiment.result.ExperimentResult;
import io.github.markpollack.experiment.result.ItemResult;
import io.github.markpollack.experiment.result.KnowledgeFileEntry;
import io.github.markpollack.experiment.result.KnowledgeManifest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSystemResultStoreTest {

	@TempDir
	Path tempDir;

	private FileSystemResultStore store;

	@BeforeEach
	void setUp() {
		store = new FileSystemResultStore(tempDir);
	}

	@Test
	void saveCreatesJsonFileAndIndex() {
		ExperimentResult result = resultWith("exp-001", "my-experiment", "2026-01-15T10:00:00Z");

		store.save(result);

		assertThat(tempDir.resolve("my-experiment/exp-001.json")).isRegularFile();
		assertThat(tempDir.resolve("my-experiment/index.json")).isRegularFile();
	}

	@Test
	void loadByIdFindsResult() {
		ExperimentResult result = resultWith("exp-001", "my-experiment", "2026-01-15T10:00:00Z");
		store.save(result);

		Optional<ExperimentResult> loaded = store.load("exp-001");

		assertThat(loaded).isPresent();
		assertThat(loaded.get().experimentId()).isEqualTo("exp-001");
		assertThat(loaded.get().experimentName()).isEqualTo("my-experiment");
	}

	@Test
	void loadReturnsEmptyForMissingId() {
		Optional<ExperimentResult> loaded = store.load("nonexistent");

		assertThat(loaded).isEmpty();
	}

	@Test
	void loadScansAcrossExperimentDirectories() {
		store.save(resultWith("exp-001", "experiment-a", "2026-01-15T10:00:00Z"));
		store.save(resultWith("exp-002", "experiment-b", "2026-01-15T11:00:00Z"));

		assertThat(store.load("exp-001")).isPresent();
		assertThat(store.load("exp-002")).isPresent();
	}

	@Test
	void listByNameReturnsResultsSortedByTimestamp() {
		store.save(resultWith("exp-003", "my-experiment", "2026-01-15T12:00:00Z"));
		store.save(resultWith("exp-001", "my-experiment", "2026-01-15T10:00:00Z"));
		store.save(resultWith("exp-002", "my-experiment", "2026-01-15T11:00:00Z"));

		List<ExperimentResult> results = store.listByName("my-experiment");

		assertThat(results).hasSize(3);
		assertThat(results.get(0).experimentId()).isEqualTo("exp-001");
		assertThat(results.get(1).experimentId()).isEqualTo("exp-002");
		assertThat(results.get(2).experimentId()).isEqualTo("exp-003");
	}

	@Test
	void listByNameReturnsEmptyForUnknownExperiment() {
		List<ExperimentResult> results = store.listByName("nonexistent");

		assertThat(results).isEmpty();
	}

	@Test
	void mostRecentReturnsLatestByTimestamp() {
		store.save(resultWith("exp-001", "my-experiment", "2026-01-15T10:00:00Z"));
		store.save(resultWith("exp-002", "my-experiment", "2026-01-15T12:00:00Z"));
		store.save(resultWith("exp-003", "my-experiment", "2026-01-15T11:00:00Z"));

		Optional<ExperimentResult> mostRecent = store.mostRecent("my-experiment");

		assertThat(mostRecent).isPresent();
		assertThat(mostRecent.get().experimentId()).isEqualTo("exp-002");
	}

	@Test
	void mostRecentReturnsEmptyForUnknownExperiment() {
		Optional<ExperimentResult> mostRecent = store.mostRecent("nonexistent");

		assertThat(mostRecent).isEmpty();
	}

	@Test
	void saveOverwritesExistingResult() {
		ExperimentResult original = resultWith("exp-001", "my-experiment", "2026-01-15T10:00:00Z");
		store.save(original);

		ExperimentResult updated = ExperimentResult.builder()
			.experimentId("exp-001")
			.experimentName("my-experiment")
			.datasetDirty(false)
			.datasetSemanticVersion("2.0.0")
			.timestamp(Instant.parse("2026-01-15T10:00:00Z"))
			.items(List.of())
			.metadata(Map.of())
			.aggregateScores(Map.of())
			.passRate(0.5)
			.build();
		store.save(updated);

		Optional<ExperimentResult> loaded = store.load("exp-001");
		assertThat(loaded).isPresent();
		assertThat(loaded.get().datasetSemanticVersion()).isEqualTo("2.0.0");

		// Index should not have duplicate entries after re-save
		assertThat(store.listByName("my-experiment")).hasSize(1);
	}

	@Test
	void roundTripsFullResultWithItemsAndVerdict() {
		ItemResult item = ItemResult.builder()
			.itemId("ITEM-001")
			.itemSlug("simple-rename")
			.success(true)
			.passed(true)
			.costUsd(0.05)
			.totalTokens(350)
			.durationMs(5000)
			.scores(Map.of("build", 1.0, "file_comparison", 0.85))
			.metrics(Map.of("input_tokens", 100, "output_tokens", 200))
			.metadata(Map.of())
			.build();

		ExperimentResult original = ExperimentResult.builder()
			.experimentId("full-test")
			.experimentName("round-trip")
			.datasetVersion("abc123def")
			.datasetDirty(false)
			.datasetSemanticVersion("1.0.0")
			.timestamp(Instant.parse("2026-01-15T10:00:00Z"))
			.items(List.of(item))
			.metadata(Map.of("model", "opus", "run", "1"))
			.aggregateScores(Map.of("build", 1.0, "file_comparison", 0.85))
			.passRate(1.0)
			.totalCostUsd(0.05)
			.totalTokens(350)
			.totalDurationMs(5000)
			.build();

		store.save(original);
		Optional<ExperimentResult> loaded = store.load("full-test");

		assertThat(loaded).isPresent();
		ExperimentResult restored = loaded.get();
		assertThat(restored.items()).hasSize(1);
		assertThat(restored.items().get(0).scores()).containsEntry("build", 1.0);
		assertThat(restored.aggregateScores()).containsEntry("file_comparison", 0.85);
		assertThat(restored.metadata()).containsEntry("model", "opus");
	}

	@Test
	void savesAndLoadsResultWithKnowledgeManifest() {
		KnowledgeManifest manifest = new KnowledgeManifest(Path.of("/tmp/knowledge-store"), "abc123def", false,
				Instant.parse("2026-02-21T10:00:00Z"), List.of(new KnowledgeFileEntry("spring/boot-migration.md", 4096),
						new KnowledgeFileEntry("jakarta/servlet-api.md", 2048)),
				List.of("*.bak"));

		ExperimentResult original = ExperimentResult.builder()
			.experimentId("kb-test")
			.experimentName("kb-round-trip")
			.datasetDirty(false)
			.datasetSemanticVersion("1.0.0")
			.knowledgeManifest(manifest)
			.timestamp(Instant.parse("2026-02-21T10:00:00Z"))
			.items(List.of())
			.metadata(Map.of())
			.aggregateScores(Map.of())
			.passRate(0.0)
			.build();

		store.save(original);
		Optional<ExperimentResult> loaded = store.load("kb-test");

		assertThat(loaded).isPresent();
		ExperimentResult restored = loaded.get();
		assertThat(restored.knowledgeManifest()).isNotNull();
		KnowledgeManifest restoredManifest = restored.knowledgeManifest();
		assertThat(restoredManifest.rootDir()).isEqualTo(Path.of("/tmp/knowledge-store"));
		assertThat(restoredManifest.repoCommit()).isEqualTo("abc123def");
		assertThat(restoredManifest.dirty()).isFalse();
		assertThat(restoredManifest.snapshotTimestamp()).isEqualTo(Instant.parse("2026-02-21T10:00:00Z"));
		assertThat(restoredManifest.files()).hasSize(2);
		assertThat(restoredManifest.files().get(0).relativePath()).isEqualTo("spring/boot-migration.md");
		assertThat(restoredManifest.files().get(0).sizeBytes()).isEqualTo(4096);
		assertThat(restoredManifest.exclusions()).containsExactly("*.bak");
	}

	private static ExperimentResult resultWith(String id, String name, String timestamp) {
		return ExperimentResult.builder()
			.experimentId(id)
			.experimentName(name)
			.datasetDirty(false)
			.datasetSemanticVersion("1.0.0")
			.timestamp(Instant.parse(timestamp))
			.items(List.of())
			.metadata(Map.of())
			.aggregateScores(Map.of())
			.passRate(0.0)
			.build();
	}

}
