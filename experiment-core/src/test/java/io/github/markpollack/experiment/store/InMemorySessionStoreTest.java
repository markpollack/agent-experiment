package io.github.markpollack.experiment.store;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.markpollack.experiment.result.ExperimentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemorySessionStoreTest {

	private InMemorySessionStore store;

	@BeforeEach
	void setUp() {
		store = new InMemorySessionStore();
	}

	@Test
	void createAndLoadSession() {
		store.createSession("run-1", "exp", Map.of("key", "val"));

		Optional<RunSession> loaded = store.loadSession("exp", "run-1");
		assertThat(loaded).isPresent();
		assertThat(loaded.get().sessionName()).isEqualTo("run-1");
		assertThat(loaded.get().status()).isEqualTo(RunSessionStatus.RUNNING);
		assertThat(loaded.get().metadata()).containsEntry("key", "val");
	}

	@Test
	void saveVariantUpdatesSession() {
		store.createSession("run-1", "exp", Map.of());
		store.saveVariantToSession("run-1", "exp", "control", minimalResult("id-1", "exp"));

		RunSession session = store.loadSession("exp", "run-1").orElseThrow();
		assertThat(session.variants()).hasSize(1);
		assertThat(session.variants().get(0).variantName()).isEqualTo("control");
	}

	@Test
	void finalizeSessionSetsCompletedAt() {
		store.createSession("run-1", "exp", Map.of());
		store.finalizeSession("run-1", "exp", RunSessionStatus.COMPLETED);

		RunSession session = store.loadSession("exp", "run-1").orElseThrow();
		assertThat(session.status()).isEqualTo(RunSessionStatus.COMPLETED);
		assertThat(session.completedAt()).isNotNull();
	}

	@Test
	void saveVariantToNonexistentSessionThrows() {
		assertThatThrownBy(() -> store.saveVariantToSession("no-such", "exp", "control", minimalResult("id-1", "exp")))
			.isInstanceOf(ResultStoreException.class);
	}

	@Test
	void sizeAndClear() {
		store.createSession("run-1", "exp-a", Map.of());
		store.createSession("run-2", "exp-b", Map.of());
		assertThat(store.size()).isEqualTo(2);

		store.clear();
		assertThat(store.size()).isZero();
	}

	@Test
	void listSessionsFiltersbyExperiment() {
		store.createSession("run-1", "exp-a", Map.of());
		store.createSession("run-2", "exp-b", Map.of());

		List<RunSession> sessions = store.listSessions("exp-a");
		assertThat(sessions).hasSize(1);
		assertThat(sessions.get(0).experimentName()).isEqualTo("exp-a");
	}

	private static ExperimentResult minimalResult(String id, String experimentName) {
		return ExperimentResult.builder()
			.experimentId(id)
			.experimentName(experimentName)
			.datasetVersion("abc123")
			.datasetDirty(false)
			.datasetSemanticVersion("1.0.0")
			.timestamp(Instant.parse("2026-03-03T10:00:00Z"))
			.items(List.of())
			.metadata(Map.of())
			.aggregateScores(Map.of())
			.passRate(1.0)
			.totalCostUsd(0.05)
			.totalTokens(350)
			.totalDurationMs(5000)
			.build();
	}

}
