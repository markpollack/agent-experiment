package io.github.markpollack.experiment.store;

/**
 * Status of a {@link Sweep}. Includes {@link #PARTIAL} for sweeps where some but not all
 * expected variants have been resolved.
 */
public enum SweepStatus {

	RUNNING, PARTIAL, COMPLETED, FAILED

}
