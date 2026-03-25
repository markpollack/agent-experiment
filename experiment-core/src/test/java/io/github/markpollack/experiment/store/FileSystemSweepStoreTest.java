package io.github.markpollack.experiment.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.markpollack.experiment.result.ExperimentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSystemSweepStoreTest {

	@TempDir
	Path tempDir;

	private FileSystemSessionStore sessionStore;

	private FileSystemSweepStore sweepStore;

	@BeforeEach
	void setUp() {
		sessionStore = new FileSystemSessionStore(tempDir);
		sweepStore = new FileSystemSweepStore(tempDir, sessionStore);
	}

	@Test
	void createSweepWritesSweepJson() {
		Sweep sweep = sweepStore.createSweep("stage5-full", "petclinic", List.of("control", "variant-a"), Map.of());

		assertThat(sweep.sweepName()).isEqualTo("stage5-full");
		assertThat(sweep.experimentName()).isEqualTo("petclinic");
		assertThat(sweep.status()).isEqualTo(SweepStatus.RUNNING);
		assertThat(sweep.expectedVariants()).containsExactly("control", "variant-a");
		assertThat(sweep.resolutions()).hasSize(2);
		assertThat(sweep.resolutions()).allSatisfy(r -> assertThat(r.isResolved()).isFalse());
		assertThat(sweep.sessionHistory()).isEmpty();
		assertThat(sweep.completedAt()).isNull();

		Path sweepFile = tempDir.resolve("petclinic/sweeps/stage5-full/sweep.json");
		assertThat(sweepFile).exists();
	}

	@Test
	void createSweepWritesSweepsIndex() {
		sweepStore.createSweep("stage5-full", "petclinic", List.of("control"), Map.of());

		Path indexFile = tempDir.resolve("petclinic/sweeps-index.json");
		assertThat(indexFile).exists();
	}

	@Test
	void createSweepWithEmptyVariantsThrows() {
		assertThatThrownBy(() -> sweepStore.createSweep("test", "exp", List.of(), Map.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("empty");
	}

	@Test
	void addSessionResolvesMatchingVariants() {
		sweepStore.createSweep("stage5", "petclinic", List.of("control", "variant-a"), Map.of());

		sessionStore.createSession("session-1", "petclinic", Map.of());
		sessionStore.saveVariantToSession("session-1", "petclinic", "control", minimalResult("id1", "petclinic"));

		sweepStore.addSession("stage5", "petclinic", "session-1", "abc123");

		Sweep sweep = sweepStore.loadSweep("petclinic", "stage5").orElseThrow();
		assertThat(sweep.resolutions()).anySatisfy(r -> {
			assertThat(r.variantName()).isEqualTo("control");
			assertThat(r.sessionName()).isEqualTo("session-1");
			assertThat(r.gitCommit()).isEqualTo("abc123");
			assertThat(r.isResolved()).isTrue();
		});
		assertThat(sweep.resolutions()).anySatisfy(r -> {
			assertThat(r.variantName()).isEqualTo("variant-a");
			assertThat(r.isResolved()).isFalse();
		});
	}

	@Test
	void addSessionLastWriteWinsReplacesResolution() {
		sweepStore.createSweep("stage5", "petclinic", List.of("control"), Map.of());

		sessionStore.createSession("session-1", "petclinic", Map.of());
		sessionStore.saveVariantToSession("session-1", "petclinic", "control", minimalResult("id1", "petclinic"));
		sweepStore.addSession("stage5", "petclinic", "session-1", "abc123");

		sessionStore.createSession("session-2", "petclinic", Map.of());
		sessionStore.saveVariantToSession("session-2", "petclinic", "control", minimalResult("id2", "petclinic"));
		sweepStore.addSession("stage5", "petclinic", "session-2", "def456");

		Sweep sweep = sweepStore.loadSweep("petclinic", "stage5").orElseThrow();
		SweepVariantResolution controlResolution = sweep.resolutions()
			.stream()
			.filter(r -> r.variantName().equals("control"))
			.findFirst()
			.orElseThrow();
		assertThat(controlResolution.sessionName()).isEqualTo("session-2");
		assertThat(controlResolution.gitCommit()).isEqualTo("def456");
	}

	@Test
	void removeSessionRevertsToUnresolved() {
		sweepStore.createSweep("stage5", "petclinic", List.of("control", "variant-a"), Map.of());

		sessionStore.createSession("session-1", "petclinic", Map.of());
		sessionStore.saveVariantToSession("session-1", "petclinic", "control", minimalResult("id1", "petclinic"));
		sweepStore.addSession("stage5", "petclinic", "session-1", null);

		sweepStore.removeSession("stage5", "petclinic", "session-1");

		Sweep sweep = sweepStore.loadSweep("petclinic", "stage5").orElseThrow();
		assertThat(sweep.resolutions()).allSatisfy(r -> assertThat(r.isResolved()).isFalse());
	}

	@Test
	void sessionHistoryIsAppendOnly() {
		sweepStore.createSweep("stage5", "petclinic", List.of("control"), Map.of());

		sessionStore.createSession("session-1", "petclinic", Map.of());
		sessionStore.saveVariantToSession("session-1", "petclinic", "control", minimalResult("id1", "petclinic"));
		sweepStore.addSession("stage5", "petclinic", "session-1", null);

		sessionStore.createSession("session-2", "petclinic", Map.of());
		sessionStore.saveVariantToSession("session-2", "petclinic", "control", minimalResult("id2", "petclinic"));
		sweepStore.addSession("stage5", "petclinic", "session-2", null);

		// Remove session-1 — it stays in history
		sweepStore.removeSession("stage5", "petclinic", "session-1");

		Sweep sweep = sweepStore.loadSweep("petclinic", "stage5").orElseThrow();
		assertThat(sweep.sessionHistory()).containsExactly("session-1", "session-2");
	}

	@Test
	void addSessionAutoTransitionsToPartial() {
		sweepStore.createSweep("stage5", "petclinic", List.of("control", "variant-a"), Map.of());

		Sweep beforeAdd = sweepStore.loadSweep("petclinic", "stage5").orElseThrow();
		assertThat(beforeAdd.status()).isEqualTo(SweepStatus.RUNNING);

		sessionStore.createSession("session-1", "petclinic", Map.of());
		sessionStore.saveVariantToSession("session-1", "petclinic", "control", minimalResult("id1", "petclinic"));
		sweepStore.addSession("stage5", "petclinic", "session-1", null);

		Sweep afterAdd = sweepStore.loadSweep("petclinic", "stage5").orElseThrow();
		assertThat(afterAdd.status()).isEqualTo(SweepStatus.PARTIAL);
	}

	@Test
	void finalizeSweepSetsCompletedStatus() {
		sweepStore.createSweep("stage5", "petclinic", List.of("control"), Map.of());

		sweepStore.finalizeSweep("stage5", "petclinic", SweepStatus.COMPLETED);

		Sweep sweep = sweepStore.loadSweep("petclinic", "stage5").orElseThrow();
		assertThat(sweep.status()).isEqualTo(SweepStatus.COMPLETED);
		assertThat(sweep.completedAt()).isNotNull();
	}

	@Test
	void hasVersionMismatchWithDifferentGitCommits() {
		sweepStore.createSweep("stage5", "petclinic", List.of("control", "variant-a"), Map.of());

		sessionStore.createSession("session-1", "petclinic", Map.of());
		sessionStore.saveVariantToSession("session-1", "petclinic", "control", minimalResult("id1", "petclinic"));
		sweepStore.addSession("stage5", "petclinic", "session-1", "abc123");

		sessionStore.createSession("session-2", "petclinic", Map.of());
		sessionStore.saveVariantToSession("session-2", "petclinic", "variant-a", minimalResult("id2", "petclinic"));
		sweepStore.addSession("stage5", "petclinic", "session-2", "def456");

		Sweep sweep = sweepStore.loadSweep("petclinic", "stage5").orElseThrow();
		assertThat(sweep.hasVersionMismatch()).isTrue();
	}

	@Test
	void directoryLayoutMatchesExpected() {
		sweepStore.createSweep("stage5-full", "petclinic", List.of("control"), Map.of());

		assertThat(tempDir.resolve("petclinic/sweeps/stage5-full/sweep.json")).exists();
		assertThat(tempDir.resolve("petclinic/sweeps-index.json")).exists();
	}

	@Test
	void listSweepsOrderedByCreation() {
		sweepStore.createSweep("sweep-a", "petclinic", List.of("control"), Map.of());
		sweepStore.createSweep("sweep-b", "petclinic", List.of("variant-a"), Map.of());

		List<Sweep> sweeps = sweepStore.listSweeps("petclinic");
		assertThat(sweeps).hasSize(2);
		assertThat(sweeps.get(0).sweepName()).isEqualTo("sweep-a");
		assertThat(sweeps.get(1).sweepName()).isEqualTo("sweep-b");
	}

	@Test
	void loadSweepReturnsEmptyForNonexistent() {
		Optional<Sweep> result = sweepStore.loadSweep("petclinic", "nonexistent");
		assertThat(result).isEmpty();
	}

	@Test
	void extraSessionVariantsSilentlyIgnored() {
		sweepStore.createSweep("stage5", "petclinic", List.of("control"), Map.of());

		sessionStore.createSession("session-1", "petclinic", Map.of());
		sessionStore.saveVariantToSession("session-1", "petclinic", "control", minimalResult("id1", "petclinic"));
		sessionStore.saveVariantToSession("session-1", "petclinic", "extra-variant", minimalResult("id2", "petclinic"));
		sweepStore.addSession("stage5", "petclinic", "session-1", null);

		Sweep sweep = sweepStore.loadSweep("petclinic", "stage5").orElseThrow();
		assertThat(sweep.resolutions()).hasSize(1);
		assertThat(sweep.resolutions().get(0).variantName()).isEqualTo("control");
		assertThat(sweep.resolutions().get(0).isResolved()).isTrue();
	}

	@Test
	void listSweepsReturnsEmptyForUnknownExperiment() {
		List<Sweep> sweeps = sweepStore.listSweeps("nonexistent");
		assertThat(sweeps).isEmpty();
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
