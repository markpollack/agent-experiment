package io.github.markpollack.experiment.store;

/**
 * Per-variant metadata within a {@link RunSession}. References the variant's result file
 * and captures summary statistics for quick session-level aggregation.
 *
 * @param variantName human-readable variant name (e.g., "control", "variant-a")
 * @param experimentId the unique experiment run ID for this variant
 * @param resultFile the result file name relative to the session directory
 * @param passRate fraction of items that passed (0.0–1.0)
 * @param itemCount number of dataset items evaluated
 * @param costUsd total cost in USD for this variant
 * @param durationMs total wall-clock duration in milliseconds
 */
public record VariantEntry(String variantName, String experimentId, String resultFile, double passRate, int itemCount,
		double costUsd, long durationMs) {
}
