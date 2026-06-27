package io.github.markpollack.experiment.journal;

import io.github.markpollack.journal.claude.PhaseCapture;

/**
 * A single item's run journal: the seam through which {@code AgentExperiment} records the
 * canonical agent-journal trace for one dataset item, then finalizes it.
 *
 * <p>
 * Recording a {@link PhaseCapture} writes the immutable execution events
 * ({@code events.jsonl}) and the derived per-step {@code StepCostEvent}s
 * ({@code analysis.jsonl}, keyed by tool_use id) — exactly what the measurement ETL
 * consumes (DESIGN §3). The <strong>invoker stays dumb</strong>: it keeps returning
 * {@link PhaseCapture}; the framework owns this lifecycle so no experiment author has to
 * remember it (DESIGN §1 root cause).
 *
 * <p>
 * Always used as an {@link AutoCloseable} around an item's phases: <pre>{@code
 * try (RunJournal journal = experimentJournal.openItem(itemId, itemSlug, model)) {
 *     for (PhaseCapture phase : invocationResult.phases()) {
 *         journal.recordPhase(phase);
 *     }
 *     journal.finish();
 * }
 * }</pre>
 *
 * @see ExperimentJournal
 */
public interface RunJournal extends AutoCloseable {

	/**
	 * Records one phase: its immutable execution events and the derived per-step cost
	 * ({@code StepCostEvent} → {@code analysis.jsonl}, keyed by tool_use id).
	 * @param phase the phase capture returned by the (dumb) invoker
	 */
	void recordPhase(PhaseCapture phase);

	/**
	 * Finishes the run. Honors the slice-1 fail-loud contract: if derived events were
	 * produced but the backing storage can't durably persist them, this throws (DESIGN
	 * §4).
	 */
	void finish();

	/**
	 * Finalizes the run if {@link #finish()} was not called (e.g. on an exception path).
	 */
	@Override
	void close();

	/**
	 * A no-op journal — records nothing and never touches the filesystem. Used for
	 * opt-out ({@code withoutJournal()}), judge/lightweight runs, and runs without an
	 * output directory.
	 * @return the shared no-op instance
	 */
	static RunJournal noop() {
		return NoOpRunJournal.INSTANCE;
	}

}
