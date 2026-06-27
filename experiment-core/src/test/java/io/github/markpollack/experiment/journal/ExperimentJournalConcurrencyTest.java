package io.github.markpollack.experiment.journal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.claude.PhaseCapture;
import io.github.markpollack.journal.claude.ToolUseRecord;
import io.github.markpollack.journal.claude.TurnUsage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency test for the run-journal lifecycle. {@code AgentExperiment} processes items
 * sequentially within a single run, but a sweep runs several arms (separate
 * {@code AgentExperiment.run(...)} calls) concurrently — each opening a
 * {@link ExperimentJournal} that reconfigures the <em>process-global</em> {@link Journal}
 * context. This is exactly where a mistake would leak one run's events into another run's
 * journal.
 *
 * <p>
 * N arms run concurrently, each on its own storage directory, each recording phases with
 * tool_use ids unique to that arm. The test asserts every arm's {@code analysis.jsonl}
 * contains exactly its own step ids (no cross-arm leakage) and its {@code run.json}
 * carries its own variant — proving the configure→start→restore-under-lock swap binds
 * each run to its own storage.
 */
class ExperimentJournalConcurrencyTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final int ARMS = 8;

	private static final int ITEMS_PER_ARM = 5;

	@BeforeEach
	void resetBefore() {
		Journal.reset();
	}

	@AfterEach
	void resetAfter() {
		Journal.reset();
	}

	@Test
	void concurrentArmsProduceIsolatedJournalsWithNoCrossRunLeakage(@TempDir Path root) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(ARMS);
		CyclicBarrier startTogether = new CyclicBarrier(ARMS);
		try {
			List<Future<Path>> futures = new ArrayList<>();
			for (int a = 0; a < ARMS; a++) {
				final int arm = a;
				futures.add(pool.submit((Callable<Path>) () -> runArm(root, arm, startTogether)));
			}

			for (int a = 0; a < ARMS; a++) {
				Path armDir = futures.get(a).get();

				// Every step id under this arm's dir must be exactly this arm's ids —
				// nothing else.
				Set<String> actual = stepIdsUnder(armDir);
				Set<String> expected = expectedStepIds(a);
				assertThat(actual).as("arm %d analysis.jsonl step ids", a).isEqualTo(expected);

				// And the arm label is recoverable from each run.json, not from some
				// other arm.
				for (Path runJson : findFiles(armDir, "run.json")) {
					JsonNode config = MAPPER.readTree(runJson.toFile()).get("config");
					assertThat(config.get("variant").asText()).isEqualTo("arm-" + a);
				}
			}
		}
		finally {
			pool.shutdownNow();
		}
	}

	private Path runArm(Path root, int arm, CyclicBarrier startTogether) throws Exception {
		Path armRoot = root.resolve("arm-" + arm);
		ExperimentJournal journal = ExperimentJournal.open(ExperimentJournal.journalRoot(armRoot), "sweep-exp");
		startTogether.await();
		for (int k = 0; k < ITEMS_PER_ARM; k++) {
			String itemId = "item-" + arm + "-" + k;
			try (RunJournal run = journal.openItem(itemId, itemId, "opus", "arm-" + arm, "sweep-1")) {
				run.recordPhase(phaseWithTools(toolA(arm, k), toolB(arm, k)));
				run.finish();
			}
		}
		return armRoot;
	}

	private static Set<String> expectedStepIds(int arm) {
		Set<String> ids = new TreeSet<>();
		for (int k = 0; k < ITEMS_PER_ARM; k++) {
			ids.add(toolA(arm, k));
			ids.add(toolB(arm, k));
		}
		return ids;
	}

	private static String toolA(int arm, int k) {
		return "toolu-" + arm + "-" + k + "-a";
	}

	private static String toolB(int arm, int k) {
		return "toolu-" + arm + "-" + k + "-b";
	}

	private Set<String> stepIdsUnder(Path armDir) throws Exception {
		Set<String> ids = new TreeSet<>();
		for (Path analysis : findFiles(armDir, "analysis.jsonl")) {
			for (String line : Files.readAllLines(analysis)) {
				if (line.isBlank()) {
					continue;
				}
				JsonNode node = MAPPER.readTree(line);
				JsonNode type = node.get("@type");
				if (type != null && "step_cost".equals(type.asText())) {
					ids.add(node.get("stepId").asText());
				}
			}
		}
		return ids;
	}

	private static PhaseCapture phaseWithTools(String id1, String id2) {
		ToolUseRecord t1 = new ToolUseRecord(id1, "Read", Map.of());
		ToolUseRecord t2 = new ToolUseRecord(id2, "Bash", Map.of());
		TurnUsage turn = new TurnUsage("msg-1", "claude-opus-4-8", 1000, 200, 0, 0, List.of(id1, id2));
		return new PhaseCapture("explore", "task", 1000, 200, 0, 0, 0, 1500L, 1200L, 0.02, "sess", 1, false, "done",
				List.of(), List.of(t1, t2), "raw", List.of(), List.of(turn), List.of());
	}

	private static List<Path> findFiles(Path root, String fileName) {
		try (Stream<Path> walk = Files.walk(root)) {
			return walk.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().equals(fileName)).toList();
		}
		catch (java.io.IOException ex) {
			throw new java.io.UncheckedIOException(ex);
		}
	}

}
