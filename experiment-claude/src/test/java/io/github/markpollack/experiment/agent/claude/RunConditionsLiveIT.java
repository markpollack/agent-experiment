package io.github.markpollack.experiment.agent.claude;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import io.github.markpollack.experiment.comparison.DefaultComparisonEngine;
import io.github.markpollack.experiment.comparison.IncomparableConditionsException;
import io.github.markpollack.experiment.dataset.FileSystemDatasetManager;
import io.github.markpollack.experiment.result.ExperimentResult;
import io.github.markpollack.experiment.runner.AgentExperiment;
import io.github.markpollack.experiment.runner.ExperimentConfig;
import io.github.markpollack.experiment.store.FileSystemResultStore;
import io.github.markpollack.judge.jury.Jury;
import io.github.markpollack.judge.jury.SimpleJury;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The exit criterion for run conditions, executed rather than asserted in the abstract.
 *
 * <p>
 * Two real runs through {@link AgentExperiment} at <b>different turn ceilings</b>,
 * persisted to a real {@link FileSystemResultStore}, then read back <b>off disk</b>. One
 * run showing a value would prove nothing — a field that always reports the same number
 * passes a single-run check — so this asserts the two records differ, and that the
 * comparison engine then refuses to compare them.
 *
 * <p>
 * This is the case that produced a withdrawn headline: two runs permitted different
 * amounts of work, compared as though alike, with nothing in the record saying the
 * ceilings differed.
 */
class RunConditionsLiveIT {

	@Test
	void twoRunsAtDifferentCeilingsRecordDifferentCeilingsAndCannotBeCompared(@TempDir Path tmp) throws Exception {
		Path datasetDir = tmp.resolve("dataset");
		writeSingleItemDataset(datasetDir);
		Path resultsDir = tmp.resolve("results");
		FileSystemResultStore store = new FileSystemResultStore(resultsDir);

		ExperimentResult tight = runAt(3, datasetDir, store);
		ExperimentResult loose = runAt(25, datasetDir, store);

		// Read the records back OFF DISK, not from the in-memory objects just returned.
		ExperimentResult tightOnDisk = store.load(tight.experimentId()).orElseThrow();
		ExperimentResult looseOnDisk = store.load(loose.experimentId()).orElseThrow();

		String tightCeiling = tightOnDisk.conditions().values().get("invoker.maxTurns");
		String looseCeiling = looseOnDisk.conditions().values().get("invoker.maxTurns");
		System.out.println("  ceiling recorded for run 1: " + tightCeiling);
		System.out.println("  ceiling recorded for run 2: " + looseCeiling);

		assertThat(tightCeiling).isEqualTo("3");
		assertThat(looseCeiling).isEqualTo("25");
		assertThat(tightOnDisk.conditions().complete()).isTrue();

		assertThatThrownBy(() -> new DefaultComparisonEngine(store).compare(tightOnDisk, looseOnDisk))
			.isInstanceOf(IncomparableConditionsException.class)
			.hasMessageContaining("invoker.maxTurns");
	}

	private ExperimentResult runAt(int maxTurns, Path datasetDir, FileSystemResultStore store) {
		ClaudeSdkInvokerConfig invokerConfig = ClaudeSdkInvokerConfig.builder()
			.maxTurns(maxTurns)
			.maxBudgetUsd(0.10)
			.build();
		ExperimentConfig config = ExperimentConfig.builder()
			.experimentName("run-conditions-ceiling-" + maxTurns)
			.datasetDir(datasetDir)
			.model("claude-sonnet-4-5")
			.promptTemplate("{{developerTask}}")
			.perItemTimeout(Duration.ofMinutes(5))
			.requireCleanGit(false)
			.withoutJournal()
			.build();
		Jury jury = SimpleJury.builder()
			.judge(new AlwaysPassing(), 1.0)
			.votingStrategy(new io.github.markpollack.judge.jury.MajorityVotingStrategy())
			.build();
		return new AgentExperiment(new FileSystemDatasetManager(), jury, store, config)
			.run(new ClaudeSdkInvoker(invokerConfig));
	}

	/**
	 * A jury is mandatory, so the demonstration supplies the smallest one that satisfies
	 * it.
	 */
	private static final class AlwaysPassing extends io.github.markpollack.judge.DeterministicJudge {

		AlwaysPassing() {
			super("ceiling_demo", "Passes unconditionally; this run demonstrates conditions, not scoring");
		}

		@Override
		public io.github.markpollack.judge.result.Judgment judge(
				io.github.markpollack.judge.context.JudgmentContext context) {
			return io.github.markpollack.judge.result.Judgment.builder().pass().reasoning("demo").build();
		}

	}

	private void writeSingleItemDataset(Path datasetDir) throws Exception {
		Files.createDirectories(datasetDir.resolve("items/CEIL-001"));
		Files.writeString(datasetDir.resolve("dataset.json"), """
				{"name": "run-conditions", "description": "ceiling demonstration",
				 "schemaVersion": 1, "version": "1.0.0",
				 "items": [{"id": "CEIL-001", "slug": "say-hello", "path": "items/CEIL-001",
				            "bucket": "A", "taskType": "no-op", "status": "active"}]}
				""");
		Files.writeString(datasetDir.resolve("items/CEIL-001/item.json"), """
				{"schemaVersion": 1, "id": "CEIL-001", "slug": "say-hello",
				 "developerTask": "Reply with exactly the word: hello. Do not use any tools.",
				 "taskType": "no-op", "bucket": "A", "noChange": true,
				 "knowledgeRefs": [], "tags": [], "status": "active"}
				""");
	}

}
