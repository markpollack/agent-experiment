package io.github.markpollack.experiment.journal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import io.github.markpollack.journal.claude.PhaseCapture;
import io.github.markpollack.journal.claude.ToolUseRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The provider session id must reach <em>disk</em>.
 *
 * <p>
 * This is deliberately not a test that {@code InvocationResult} carries the value. Tests
 * of that kind already existed and passed, and they are precisely why nobody noticed the
 * defect: on {@code origin/main} at {@code 9cf5ba2} every read of {@code .sessionId()}
 * outside its own construction was a test assertion. The value lived in memory for the
 * duration of a run and was dropped, exactly as per-tool {@code duration_ms} had been.
 *
 * <p>
 * So each test here writes a run, then reads {@code run.json} back off the filesystem and
 * asserts against its bytes. A round trip is the only assertion that would have failed
 * before this change.
 */
class ProviderSessionIdPersistenceTest {

	@Test
	void providerSessionIdReachesRunJsonOnDisk(@TempDir Path root) throws Exception {
		ExperimentJournal journal = ExperimentJournal.open(ExperimentJournal.journalRoot(root), "raw-archival-exp");
		try (RunJournal run = journal.openItem("ITEM-1", "item-1", "sonnet", "arm-a", "sweep-1",
				"3f2a9c10-7b44-4e2b-9d61-0c5e8a1b2d33")) {
			run.recordPhase(phase());
			run.finish();
		}

		String runJson = readTheOnlyRunJson(root);
		assertThat(runJson).contains("provider_session_id").contains("3f2a9c10-7b44-4e2b-9d61-0c5e8a1b2d33");
	}

	/**
	 * Absence is recorded, not omitted. An absent key says nobody recorded it; an
	 * explicit null says the provider returned none. Those are different facts for
	 * whoever reads this file a year from now, and only one of them is honest about what
	 * happened.
	 */
	@Test
	void anAbsentProviderSessionIdIsWrittenAsNullRatherThanOmitted(@TempDir Path root) throws Exception {
		ExperimentJournal journal = ExperimentJournal.open(ExperimentJournal.journalRoot(root), "raw-archival-exp");
		try (RunJournal run = journal.openItem("ITEM-2", "item-2", "sonnet", "arm-a", null, null)) {
			run.recordPhase(phase());
			run.finish();
		}

		String runJson = readTheOnlyRunJson(root);
		assertThat(runJson).as("the key must be present even when the provider returned nothing")
			.contains("provider_session_id")
			.contains(ExperimentJournal.NO_PROVIDER_SESSION);
	}

	/**
	 * The two identifiers must not be confusable in the file. {@code session} is the
	 * sweep's grouping label; {@code provider_session_id} resolves the run to its raw
	 * provider transcript. Conflating the two is the ambiguity that caused this defect,
	 * so the file has to keep them apart and visibly different.
	 */
	@Test
	void sweepSessionAndProviderSessionAreDistinctKeysWithDistinctValues(@TempDir Path root) throws Exception {
		ExperimentJournal journal = ExperimentJournal.open(ExperimentJournal.journalRoot(root), "raw-archival-exp");
		try (RunJournal run = journal.openItem("ITEM-3", "item-3", "sonnet", "arm-a", "sweep-42", "provider-uuid-99")) {
			run.recordPhase(phase());
			run.finish();
		}

		String runJson = readTheOnlyRunJson(root);
		assertThat(runJson).contains("\"session\"").contains("sweep-42");
		assertThat(runJson).contains("provider_session_id").contains("provider-uuid-99");
		assertThat(runJson).as("the sweep id must not have been written as the provider id")
			.doesNotContain("\"provider_session_id\":\"sweep-42\"");
	}

	private static PhaseCapture phase() {
		ToolUseRecord tool = new ToolUseRecord("tool-1", "Read", java.util.Map.of());
		return new PhaseCapture("explore", "task", 1000, 200, 0, 0, 0, 1500L, 1200L, 0.02,
				"3f2a9c10-7b44-4e2b-9d61-0c5e8a1b2d33", 1, false, "done", List.of(), List.of(tool), "raw", List.of(),
				List.of(), List.of());
	}

	private static String readTheOnlyRunJson(Path root) throws Exception {
		List<Path> found;
		try (Stream<Path> walk = Files.walk(root)) {
			found = walk.filter(Files::isRegularFile)
				.filter(p -> p.getFileName().toString().equals("run.json"))
				.toList();
		}
		assertThat(found).as("exactly one run.json should have been written under %s", root).hasSize(1);
		return Files.readString(found.get(0));
	}

}
