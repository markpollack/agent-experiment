package io.github.markpollack.experiment.store;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.markpollack.experiment.result.ExperimentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryResultStoreTest {

	private InMemoryResultStore store;

	@BeforeEach
	void setUp() {
		store = new InMemoryResultStore();
	}

	@Test
	void saveAndLoadById() {
		ExperimentResult result = resultWith("exp-001", "test", "2026-01-15T10:00:00Z");
		store.save(result);

		Optional<ExperimentResult> loaded = store.load("exp-001");

		assertThat(loaded).isPresent();
		assertThat(loaded.get().experimentId()).isEqualTo("exp-001");
	}

	@Test
	void loadReturnsEmptyForMissingId() {
		assertThat(store.load("nonexistent")).isEmpty();
	}

	@Test
	void listByNameReturnsResultsSortedByTimestamp() {
		store.save(resultWith("exp-003", "my-experiment", "2026-01-15T12:00:00Z"));
		store.save(resultWith("exp-001", "my-experiment", "2026-01-15T10:00:00Z"));
		store.save(resultWith("exp-002", "my-experiment", "2026-01-15T11:00:00Z"));
		store.save(resultWith("other-001", "other-experiment", "2026-01-15T09:00:00Z"));

		List<ExperimentResult> results = store.listByName("my-experiment");

		assertThat(results).hasSize(3);
		assertThat(results.get(0).experimentId()).isEqualTo("exp-001");
		assertThat(results.get(1).experimentId()).isEqualTo("exp-002");
		assertThat(results.get(2).experimentId()).isEqualTo("exp-003");
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
		assertThat(store.mostRecent("nonexistent")).isEmpty();
	}

	@Test
	void sizeAndClear() {
		store.save(resultWith("exp-001", "test", "2026-01-15T10:00:00Z"));
		store.save(resultWith("exp-002", "test", "2026-01-15T11:00:00Z"));

		assertThat(store.size()).isEqualTo(2);

		store.clear();
		assertThat(store.size()).isZero();
		assertThat(store.load("exp-001")).isEmpty();
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
