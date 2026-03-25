package io.github.markpollack.experiment.store;

/**
 * Lightweight runtime handle passed to {@code ExperimentRunner} to identify the active
 * session and variant. Immutable and stateless — used only for path computation and
 * session store writes.
 *
 * @param sessionName the session this run belongs to
 * @param experimentName the experiment name
 * @param variantName the variant being executed in this run
 */
public record ActiveSession(String sessionName, String experimentName, String variantName) {
}
