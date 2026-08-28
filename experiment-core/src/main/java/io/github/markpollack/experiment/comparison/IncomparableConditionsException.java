package io.github.markpollack.experiment.comparison;

import java.util.List;

/**
 * Thrown when two runs cannot be compared because of the conditions they ran under.
 *
 * <p>
 * Either the conditions differ — the runs were not permitted to do the same amount of
 * work — or at least one run did not record what it was permitted. Both are
 * disqualifying, and the second is the one that motivated this: a comparison was
 * published across two runs with different turn ceilings and had to be withdrawn, because
 * nothing in the record said the ceilings differed.
 */
public class IncomparableConditionsException extends RuntimeException {

	private final List<String> reasons;

	public IncomparableConditionsException(String currentId, String baselineId, List<String> reasons) {
		super("cannot compare run " + currentId + " with baseline " + baselineId + ": " + String.join("; ", reasons));
		this.reasons = List.copyOf(reasons);
	}

	/** Each reason the two runs are not comparable. */
	public List<String> reasons() {
		return this.reasons;
	}

}
