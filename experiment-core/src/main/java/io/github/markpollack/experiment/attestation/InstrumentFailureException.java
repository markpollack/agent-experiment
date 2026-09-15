package io.github.markpollack.experiment.attestation;

import java.util.List;

/**
 * Thrown when a run's jury did not convene as configured.
 *
 * <p>
 * Raised <em>after</em> the result has been saved, deliberately. The agent's work is the
 * expensive half of a run and the judging is the cheap, repeatable half, so an instrument
 * failure must not discard the agent's output: the stored result can be re-judged with
 * {@code ReEvaluator} without re-running anything. Failing silently is not the
 * alternative — a guard that only writes a line in a report is not a guard.
 *
 * @see Attestability#ROSTER_MISMATCH
 */
public class InstrumentFailureException extends RuntimeException {

	private final List<String> items;

	InstrumentFailureException(String message, List<String> items) {
		super(message);
		this.items = List.copyOf(items);
	}

	/** Ids of the items whose jury did not convene as configured. */
	public List<String> items() {
		return this.items;
	}

}
