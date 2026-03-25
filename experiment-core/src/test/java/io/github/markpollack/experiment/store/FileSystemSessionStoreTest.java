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

class FileSystemSessionStoreTest {

	@TempDir
	Path tempDir;

	private FileSystemSessionStore store;

	@BeforeEach
	void setUp() {
		store = new FileSystemSessionStore(tempDir);
	}

	@Test
	void createSessionWritesSessionJson() {
		RunSession session = store.createSession("run-2026-03-03", "my-experiment", Map.of("model", "opus"));

		assertThat(session.sessionName()).isEqualTo("run-2026-03-03");
		assertThat(session.status()).isEqualTo(RunSessionStatus.RUNNING);
		assertThat(session.variants()).isEmpty();
		assertThat(session.metadata()).containsEntry("model", "opus");

		Path sessionFile = tempDir.resolve("my-experiment/sessions/run-2026-03-03/session.json");
		assertThat(sessionFile).exists();
	}

	@Test
	void createSessionWritesSessionsIndex() {
		store.createSession("run-1", "my-experiment", Map.of());

		Path indexFile = tempDir.resolve("my-experiment/sessions-index.json");
		assertThat(indexFile).exists();
	}

	@Test
	void fullLifecycle() {
		store.createSession("suite-1", "exp", Map.of());

		store.saveVariantToSession("suite-1", "exp", "control", minimalResult("ctrl-id", "exp"));
		store.saveVariantToSession("suite-1", "exp", "variant-a", minimalResult("va-id", "exp"));

		store.finalizeSession("suite-1", "exp", RunSessionStatus.COMPLETED);

		Optional<RunSession> loaded = store.loadSession("exp", "suite-1");
		assertThat(loaded).isPresent();

		RunSession session = loaded.get();
		assertThat(session.status()).isEqualTo(RunSessionStatus.COMPLETED);
		assertThat(session.completedAt()).isNotNull();
		assertThat(session.variants()).hasSize(2);
		assertThat(session.variants().get(0).variantName()).isEqualTo("control");
		assertThat(session.variants().get(1).variantName()).isEqualTo("variant-a");
	}

	@Test
	void directoryLayoutMatchesSpec() {
		store.createSession("suite-1", "exp", Map.of());
		store.saveVariantToSession("suite-1", "exp", "control", minimalResult("ctrl-id", "exp"));

		assertThat(tempDir.resolve("exp/sessions/suite-1/session.json")).exists();
		assertThat(tempDir.resolve("exp/sessions/suite-1/control.json")).exists();
		assertThat(tempDir.resolve("exp/sessions-index.json")).exists();
	}

	@Test
	void sessionsIndexIntegrityWithMultipleSessions() {
		store.createSession("run-1", "exp", Map.of());
		store.createSession("run-2", "exp", Map.of());

		List<RunSession> sessions = store.listSessions("exp");
		assertThat(sessions).hasSize(2);
	}

	@Test
	void listSessionsOrderedByCreatedAt() {
		store.createSession("older", "exp", Map.of());
		store.createSession("newer", "exp", Map.of());

		List<RunSession> sessions = store.listSessions("exp");
		assertThat(sessions).hasSize(2);
		assertThat(sessions.get(0).createdAt()).isBeforeOrEqualTo(sessions.get(1).createdAt());
	}

	@Test
	void mostRecentSessionReturnsLatest() {
		store.createSession("first", "exp", Map.of());
		store.createSession("second", "exp", Map.of());

		Optional<RunSession> recent = store.mostRecentSession("exp");
		assertThat(recent).isPresent();
		assertThat(recent.get().sessionName()).isEqualTo("second");
	}

	@Test
	void idempotentSaveVariantReplacesEntry() {
		store.createSession("suite-1", "exp", Map.of());

		store.saveVariantToSession("suite-1", "exp", "control", minimalResult("id-1", "exp"));
		store.saveVariantToSession("suite-1", "exp", "control", minimalResult("id-2", "exp"));

		RunSession session = store.loadSession("exp", "suite-1").orElseThrow();
		assertThat(session.variants()).hasSize(1);
		assertThat(session.variants().get(0).experimentId()).isEqualTo("id-2");
	}

	@Test
	void loadSessionReturnsEmptyForNonexistent() {
		Optional<RunSession> result = store.loadSession("exp", "no-such-session");
		assertThat(result).isEmpty();
	}

	@Test
	void listSessionsReturnsEmptyForUnknownExperiment() {
		List<RunSession> sessions = store.listSessions("unknown-experiment");
		assertThat(sessions).isEmpty();
	}

	@Test
	void mostRecentSessionReturnsEmptyForUnknownExperiment() {
		Optional<RunSession> result = store.mostRecentSession("unknown-experiment");
		assertThat(result).isEmpty();
	}

	@Test
	void finalizeSessionUpdatesIndex() {
		store.createSession("suite-1", "exp", Map.of());
		store.saveVariantToSession("suite-1", "exp", "control", minimalResult("ctrl-id", "exp"));
		store.finalizeSession("suite-1", "exp", RunSessionStatus.COMPLETED);

		// Reload via listSessions to verify index was updated
		List<RunSession> sessions = store.listSessions("exp");
		assertThat(sessions).hasSize(1);
		assertThat(sessions.get(0).status()).isEqualTo(RunSessionStatus.COMPLETED);
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
