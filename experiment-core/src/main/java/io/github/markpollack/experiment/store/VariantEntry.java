package io.github.markpollack.experiment.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.markpollack.experiment.result.ItemCounts;
import org.jspecify.annotations.Nullable;

/**
 * Per-variant metadata within a {@link RunSession}. References the variant's result file
 * and captures summary statistics for quick session-level aggregation.
 *
 * <p>
 * The pass rate is derived from what the run counted, and is absent when the jury scored
 * nothing or when the run recorded no counts. It is never 0.0 in place of an absence: a
 * variant whose items were all excluded did not fail.
 *
 * @param variantName human-readable variant name (e.g., "control", "variant-a")
 * @param experimentId the unique experiment run ID for this variant
 * @param resultFile the result file name relative to the session directory
 * @param passRate passes over items the jury scored, or null when there is no rate
 * @param itemCount number of dataset items evaluated
 * @param costUsd total cost in USD for this variant
 * @param durationMs total wall-clock duration in milliseconds
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VariantEntry(String variantName, String experimentId, String resultFile, @Nullable Double passRate,
		int itemCount, double costUsd, long durationMs) {

	/** Build an entry, deriving its rate from what the run counted. */
	public static VariantEntry of(String variantName, String experimentId, String resultFile,
			@Nullable ItemCounts counts, int itemCount, double costUsd, long durationMs) {
		Double rate = counts == null ? null : counts.passRate().isPresent() ? counts.passRate().getAsDouble() : null;
		return new VariantEntry(variantName, experimentId, resultFile, rate, itemCount, costUsd, durationMs);
	}

}
