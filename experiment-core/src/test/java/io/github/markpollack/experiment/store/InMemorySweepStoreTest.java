package io.github.markpollack.experiment.store;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.markpollack.experiment.result.ExperimentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemorySweepStoreTest {

	private InMemorySessionStore sessionStore;

	private InMemorySweepStore sweepStore;

	@BeforeEach
	void setUp() {
		sessionStore = new InMemorySessionStore();
		sweepStore = new InMemorySweepStore(sessionStore);
	}

	@Test
	void createAndLoadSweep() {
		Sweep sweep = sweepStore.createSweep("stage5", "petclinic", List.of("control", "variant-a"), Map.of());

		assertThat(sweep.sweepName()).isEqualTo("stage5");
		assertThat(sweep.status()).isEqualTo(SweepStatus.RUNNING);
		assertThat(sweepStore.size()).isEqualTo(1);

		Sweep loaded = sweepStore.loadSweep("petclinic", "stage5").orElseThrow();
		assertThat(loaded.sweepName()).isEqualTo("stage5");
	}

	@Test
	void addSessionResolvesVariants() {
		sweepStore.createSweep("stage5", "petclinic", List.of("control"), Map.of());
		sessionStore.createSession("session-1", "petclinic", Map.of());
		sessionStore.saveVariantToSession("session-1", "petclinic", "control", minimalResult("id1", "petclinic"));

		sweepStore.addSession("stage5", "petclinic", "session-1", "abc123");

		Sweep sweep = sweepStore.loadSweep("petclinic", "stage5").orElseThrow();
		assertThat(sweep.resolutions().get(0).isResolved()).isTrue();
		assertThat(sweep.resolutions().get(0).sessionName()).isEqualTo("session-1");
		assertThat(sweep.status()).isEqualTo(SweepStatus.PARTIAL);
	}

	@Test
	void removeSessionRevertsResolution() {
		sweepStore.createSweep("stage5", "petclinic", List.of("control"), Map.of());
		sessionStore.createSession("session-1", "petclinic", Map.of());
		sessionStore.saveVariantToSession("session-1", "petclinic", "control", minimalResult("id1", "petclinic"));
		sweepStore.addSession("stage5", "petclinic", "session-1", null);

		sweepStore.removeSession("stage5", "petclinic", "session-1");

		Sweep sweep = sweepStore.loadSweep("petclinic", "stage5").orElseThrow();
		assertThat(sweep.resolutions().get(0).isResolved()).isFalse();
	}

	@Test
	void finalizeSweep() {
		sweepStore.createSweep("stage5", "petclinic", List.of("control"), Map.of());
		sweepStore.finalizeSweep("stage5", "petclinic", SweepStatus.COMPLETED);

		Sweep sweep = sweepStore.loadSweep("petclinic", "stage5").orElseThrow();
		assertThat(sweep.status()).isEqualTo(SweepStatus.COMPLETED);
		assertThat(sweep.completedAt()).isNotNull();
	}

	@Test
	void listSweepsReturnsOrdered() {
		sweepStore.createSweep("sweep-a", "petclinic", List.of("control"), Map.of());
		sweepStore.createSweep("sweep-b", "petclinic", List.of("control"), Map.of());

		List<Sweep> sweeps = sweepStore.listSweeps("petclinic");
		assertThat(sweeps).hasSize(2);
		assertThat(sweeps.get(0).sweepName()).isEqualTo("sweep-a");
	}

	@Test
	void clearAndSize() {
		sweepStore.createSweep("sweep-a", "petclinic", List.of("control"), Map.of());
		assertThat(sweepStore.size()).isEqualTo(1);

		sweepStore.clear();
		assertThat(sweepStore.size()).isEqualTo(0);
	}

	private static ExperimentResult minimalResult(String id, String experimentName) {
		return ExperimentResult.builder()
			.experimentId(id)
			.experimentName(experimentName)
			.datasetVersion("abc123")
			.datasetDirty(false)
			.datasetSemanticVersion("1.0.0")
			.timestamp(Instant.parse("2026-03-04T10:00:00Z"))
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
