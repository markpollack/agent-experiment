package io.github.markpollack.experiment.runner;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import io.github.markpollack.experiment.agent.*;
import io.github.markpollack.experiment.dataset.FileSystemDatasetManager;
import io.github.markpollack.experiment.store.FileSystemResultStore;
import io.github.markpollack.judge.jury.SimpleJury;
import io.github.markpollack.judge.judgment.Judgment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/**
 * Current writer migration: bare replies remain unmeasured through the actual runner and
 * store.
 */
class AgentExperimentJournalTest {

	@TempDir
	Path root;

	private ExperimentConfig.Builder config() {
		return ExperimentConfig.builder()
			.experimentName("unknown-replies")
			.datasetPath(Path.of("src/test/resources/test-dataset").toAbsolutePath())
			.model("fixture")
			.requireCleanGit(false);
	}

	@Test
	void unknownReplyTotalsAndNoLegacyEventsSurviveActualStoreReopen() throws Exception {
		var store = new FileSystemResultStore(root.resolve("results"));
		var factories = new AtomicInteger();
		var runner = new AgentExperiment(new FileSystemDatasetManager(), input -> {
			factories.incrementAndGet();
			return SimpleJury.builder().judge("fixture", () -> Judgment.pass("fixture")).build();
		}, store, config().outputDir(root.resolve("artifacts")).build());
		var result = runner.run(input -> new InvocationResult(true, TerminalStatus.COMPLETED, List.of(), 0, 0, 0, 0, 0,
				0.0, 1, "provider-session", Map.of("measurementState", "UNKNOWN", "measurementReason",
						"No qualified usage", "agentResponse", "original answer"),
				null));
		var loaded = new FileSystemResultStore(root.resolve("results")).load(result.experimentId()).orElseThrow();
		assertThat(factories).hasValue(result.items().size());
		assertThat(factories.get()).isPositive();
		assertThat(loaded.totalCostUsd()).isNull();
		assertThat(loaded.totalTokens()).isNull();
		for (var row : loaded.items()) {
			assertThat(row.costUsd()).isNull();
			assertThat(row.totalTokens()).isNull();
			assertThat(row.metrics()).containsEntry("measurementState", "UNKNOWN")
				.doesNotContainKeys("input_tokens", "output_tokens");
			assertThat(((InvocationResult) row.executionDetail()).metadata()).containsEntry("agentResponse",
					"original answer");
		}
		assertThat(store.listByName("unknown-replies")).containsExactly(loaded);
		assertThat(store.mostRecent("unknown-replies")).contains(loaded);
		try (var paths = Files.walk(root)) {
			assertThat(paths.filter(f -> f.getFileName().toString().equals("analysis.jsonl")).toList()).isEmpty();
		}
	}

	@Test
	void explicitlyRequestedRetiredWriterRefusesAtConstruction() {
		assertThatThrownBy(() -> new AgentExperiment(new FileSystemDatasetManager(),
				input -> SimpleJury.builder().judge("fixture", () -> Judgment.pass("fixture")).build(),
				new FileSystemResultStore(root), config().journalEnabled(true).build()))
			.hasMessageContaining("Legacy phase writer retired");
	}

}
