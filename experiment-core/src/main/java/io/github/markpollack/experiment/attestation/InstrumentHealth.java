package io.github.markpollack.experiment.attestation;

/**
 * What an item says about the instrument that scored it.
 *
 * <p>
 * Three states, because "not known to have failed" and "known to have been fine" are
 * different claims, and a boolean can only carry one of them. A record that never said
 * whether a stage was usable supports neither.
 */
public enum InstrumentHealth {

	/**
	 * The instrument is known to have failed: a stage failed, an aggregate errored, or
	 * the jury did not convene with the judges it lists.
	 */
	FAILED,

	/**
	 * Every fact needed to judge the instrument was recorded, and none of them is a
	 * failure.
	 */
	OK,

	/** The record does not say. Not a failure, and not a clean bill of health. */
	UNKNOWN

}
