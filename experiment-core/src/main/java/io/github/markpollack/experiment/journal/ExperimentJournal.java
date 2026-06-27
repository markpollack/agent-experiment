package io.github.markpollack.experiment.journal;

import java.nio.file.Path;

import io.github.markpollack.journal.Experiment;
import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.claude.RunRecorder;
import io.github.markpollack.journal.storage.JournalStorage;
import io.github.markpollack.journal.storage.JsonFileStorage;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the run-journal lifecycle for one experiment run — the experiment-core slice of
 * the first-class journal-capture feature (DESIGN §4: "Run-journal lifecycle = property
 * of an experiment"). It holds the canonical {@link JsonFileStorage} rooted beside the
 * experiment's artifacts and opens exactly one journal {@link Run} per dataset item.
 *
 * <h2>Canonical journal dir convention</h2>
 *
 * The journal lives under the experiment's run directory (the same artifact dir that
 * holds the run log and preserved workspaces — i.e. beside the {@code ResultStore}
 * output): <pre>
 * &lt;runDir&gt;/journal/                              ← {@link #journalRoot(Path)}; JsonFileStorage baseDir
 *   experiments/&lt;experimentName&gt;/
 *     runs/&lt;runId&gt;/
 *       run.json        ← carries config.itemId / config.itemSlug for item attribution
 *       events.jsonl    ← immutable execution events (LLMCallEvent, ToolCallEvent, …)
 *       analysis.jsonl  ← derived StepCostEvents, keyed by tool_use id
 * </pre>
 *
 * where {@code <runDir>} is {@code <outputDir>/<exp>/<experimentId>} (non-session) or
 * {@code <outputDir>/<exp>/sessions/<session>/<variant>} (session). This is a
 * discoverable, config-free path: the ETL globs
 * {@code **}{@code /journal/experiments/*}{@code /runs/*}{@code
 * /analysis.jsonl} and recovers the item from each run's {@code itemId} config. It
 * refines the frozen §4 proposal ({@code journal/<item>/…}) to the journal-core-native
 * experiment→runs layout, because run ids are library-generated UUIDs and the ETL
 * ({@code load_trace_jsonl}) is built around the experiment/run tree.
 *
 * <h2>Global context handling</h2>
 *
 * agent-journal's {@link Journal} context (storage + experiment registry) is
 * process-global. Opening a run therefore briefly installs this journal's storage, starts
 * the run (which binds that storage for the life of the run), then restores the previous
 * global — all under {@link #GLOBAL_LOCK} — so concurrent experiment runs never
 * interleave the global reconfigure with a {@code start()}, and this journal never leaks
 * its storage as the process default.
 */
public final class ExperimentJournal {

	/**
	 * Subdirectory (under the experiment run directory) holding the canonical journal.
	 */
	public static final String JOURNAL_DIR = "journal";

	private static final Logger logger = LoggerFactory.getLogger(ExperimentJournal.class);

	/**
	 * Serializes the global configure→start→restore critical section across experiment
	 * runs.
	 */
	private static final Object GLOBAL_LOCK = new Object();

	private final String experimentName;

	private final @Nullable JournalStorage storage;

	private ExperimentJournal(String experimentName, @Nullable JournalStorage storage) {
		this.experimentName = java.util.Objects.requireNonNull(experimentName, "experimentName must not be null");
		this.storage = storage;
	}

	/**
	 * Opens a durable journal rooted at {@code journalRoot} (typically
	 * {@link #journalRoot(Path)}). Writes {@code experiment.json} immediately so each
	 * per-run storage is self-contained even when the process-global experiment registry
	 * has the experiment cached from an earlier run.
	 * @param journalRoot the {@link JsonFileStorage} base directory (the {@code journal/}
	 * dir)
	 * @param experimentName the experiment id used for the journal experiment
	 * @return an enabled journal
	 */
	public static ExperimentJournal open(Path journalRoot, String experimentName) {
		JournalStorage storage = new JsonFileStorage(journalRoot);
		synchronized (GLOBAL_LOCK) {
			JournalStorage previous = Journal.storage();
			Journal.configure(storage);
			try {
				Experiment experiment = Journal.experiment(experimentName);
				storage.saveExperiment(experiment);
			}
			finally {
				Journal.configure(previous);
			}
		}
		logger.info("Journaling enabled for experiment '{}' at {}", experimentName, journalRoot);
		return new ExperimentJournal(experimentName, storage);
	}

	/**
	 * A disabled journal: {@link #openItem} always returns {@link RunJournal#noop()}.
	 * @param experimentName the experiment id (for diagnostics only)
	 * @return a disabled journal
	 */
	public static ExperimentJournal disabled(String experimentName) {
		return new ExperimentJournal(experimentName, null);
	}

	/** Whether this journal writes a durable trace (vs. a no-op). */
	public boolean enabled() {
		return storage != null;
	}

	/**
	 * Opens the journal {@link Run} for one dataset item. When disabled, returns a no-op
	 * journal.
	 * @param itemId the dataset item id (becomes the run name + {@code itemId} config)
	 * @param itemSlug the dataset item slug (recorded as {@code itemSlug} config)
	 * @param model the model id (recorded as {@code model} config — read by the recorder
	 * for LLM events)
	 * @return a journal for the item's phases
	 */
	public RunJournal openItem(String itemId, String itemSlug, String model) {
		if (storage == null) {
			return RunJournal.noop();
		}
		RunRecorder recorder;
		synchronized (GLOBAL_LOCK) {
			JournalStorage previous = Journal.storage();
			Journal.configure(storage);
			try {
				Run run = Journal.run(experimentName)
					.name(itemId)
					.config("model", model)
					.config("itemId", itemId)
					.config("itemSlug", itemSlug)
					.tag("itemId", itemId)
					.start();
				recorder = new RunRecorder(run, storage);
			}
			finally {
				Journal.configure(previous);
			}
		}
		return new JsonFileRunJournal(recorder);
	}

	/**
	 * The canonical journal root for an experiment run directory:
	 * {@code <runDir>/journal}.
	 * @param runDir the experiment run (artifact) directory
	 * @return the journal base directory
	 */
	public static Path journalRoot(Path runDir) {
		return runDir.resolve(JOURNAL_DIR);
	}

}
