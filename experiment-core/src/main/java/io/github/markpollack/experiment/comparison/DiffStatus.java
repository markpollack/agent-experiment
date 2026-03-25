package io.github.markpollack.experiment.comparison;

/**
 * Classification of an item's change between experiment runs.
 */
public enum DiffStatus {

	/** At least one judge improved, none regressed. */
	IMPROVED,

	/** At least one judge regressed. */
	REGRESSED,

	/** No significant change. */
	UNCHANGED,

	/** Item exists in current but not in baseline. */
	NEW,

	/** Item exists in baseline but not in current. */
	REMOVED

}
