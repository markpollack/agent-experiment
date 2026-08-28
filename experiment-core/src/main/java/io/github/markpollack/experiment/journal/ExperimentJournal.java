package io.github.markpollack.experiment.journal;

import java.nio.file.Path;

import io.github.markpollack.journal.Experiment;
import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.RunBuilder;
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
 *       run.json        ← config carries {variant, itemId, itemSlug, model, session?,
 *                       provider_session_id} + tags; id = runId
 *       events.jsonl    ← immutable execution events (LLMCallEvent, ToolCallEvent, …)
 *       analysis.jsonl  ← derived StepCostEvents, keyed by tool_use id
 * </pre>
 *
 * where {@code <runDir>} is {@code <outputDir>/<exp>/<experimentId>} (non-session) or
 * {@code <outputDir>/<exp>/sessions/<session>/<variant>} (session). This is a
 * discoverable, config-free path: the ETL globs
 * {@code **}{@code /journal/experiments/*}{@code /runs/*}{@code
 * /analysis.jsonl} and recovers {@code (variant, item, runId)} from each run's
 * {@code run.json} {@code config} — <em>from the journal alone</em>, no result-store
 * re-join. It refines the frozen §4 proposal ({@code journal/<item>/…}) to the
 * journal-core-native experiment→runs layout, because run ids are library-generated UUIDs
 * and the ETL ({@code load_trace_jsonl}) is built around the experiment/run tree. (The
 * journal experiment dir {@code <exp>} is the experiment <em>name</em>, not the variant —
 * per-arm grouping is by {@code config.variant}, not by directory.)
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
	/**
	 * Recorded in {@code run.json} when the provider returned no session id. Distinct
	 * from the key being absent, which means nobody recorded it at all.
	 */
	public static final String NO_PROVIDER_SESSION = "none";

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
	 *
	 * <p>
	 * The run's {@code config} is the join surface the measurement ETL (slice 4) reads
	 * from {@code run.json} — so {@code (variant, item, runId)} is recoverable <em>from
	 * the journal alone</em>, without re-joining the result store. {@code variant} is the
	 * arm/variant label cost-weighted {@code V(EXPLORE)} groups by; it is always written
	 * (the non-session single-arm sentinel is {@code "default"}). {@code model} and
	 * {@code itemId}/{@code itemSlug} round out the key.
	 * {@code variant}/{@code itemId}/{@code session} are also tags.
	 * @param itemId the dataset item id (becomes the run name + {@code itemId} config)
	 * @param itemSlug the dataset item slug (recorded as {@code itemSlug} config)
	 * @param model the model id (recorded as {@code model} config — read by the recorder
	 * for LLM events)
	 * @param variant the arm/variant label (recorded as {@code variant} config + tag);
	 * the grouping key for per-arm measurement. Never null — use {@code "default"} for
	 * single-arm runs.
	 * @param session the <em>sweep</em> session id — an experiment-side grouping label,
	 * not a provider session (recorded as {@code session} config + tag when present)
	 * @param providerSessionId the <em>provider</em> session id — the agent CLI's own
	 * session identifier, from {@code InvocationResult.sessionId()}. This is the key that
	 * resolves a run to its raw provider transcript, and it is <strong>always</strong>
	 * written to {@code run.json} as {@code provider_session_id} — when the provider
	 * returned none, as the sentinel {@link #NO_PROVIDER_SESSION}. Do not confuse it with
	 * {@code session} above; that ambiguity is why this value went unpersisted until
	 * 2026-08-27.
	 * @return a journal for the item's phases
	 */
	public RunJournal openItem(String itemId, String itemSlug, String model, String variant, @Nullable String session,
			@Nullable String providerSessionId) {
		if (storage == null) {
			return RunJournal.noop();
		}
		RunRecorder recorder;
		synchronized (GLOBAL_LOCK) {
			JournalStorage previous = Journal.storage();
			Journal.configure(storage);
			try {
				RunBuilder builder = Journal.run(experimentName)
					.name(itemId)
					.config("model", model)
					.config("itemId", itemId)
					.config("itemSlug", itemSlug)
					.config("variant", variant)
					.tag("itemId", itemId)
					.tag("variant", variant);
				if (session != null) {
					builder = builder.config("session", session).tag("session", session);
				}
				// Always written, including when the provider returned nothing. An absent
				// key and
				// a recorded absence mean different things to whoever reads run.json in a
				// year:
				// absent says nobody recorded it, "none" says the provider returned none.
				// Omitting it is how the value went missing in the first place.
				//
				// The journal's config(String, Object) rejects null, so absence is a
				// sentinel
				// rather than a JSON null. It cannot collide: provider session ids are
				// uuids.
				builder = builder.config("provider_session_id",
						providerSessionId != null ? providerSessionId : NO_PROVIDER_SESSION);
				recorder = new RunRecorder(builder.start(), storage);
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
