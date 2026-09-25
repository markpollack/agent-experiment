package io.github.markpollack.experiment.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.markpollack.experiment.result.ItemCounts;
import org.jspecify.annotations.Nullable;
import io.github.markpollack.experiment.recording.ReportingMeasures;

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
		int itemCount, @Nullable Double costUsd, @Nullable Long durationMs, @Nullable Long totalTokens,
		@Nullable ReportingMeasures measures) {

	public VariantEntry(String variantName, String experimentId, String resultFile, @Nullable Double passRate,
			int itemCount, double costUsd, long durationMs) {
		this(variantName, experimentId, resultFile, passRate, itemCount, Double.valueOf(costUsd),
				Long.valueOf(durationMs), null, null);
	}

	public static VariantEntry of(String variantName, String resultFile,
			io.github.markpollack.experiment.result.ExperimentResult result) {
		var summary = io.github.markpollack.experiment.comparison.ExperimentSummary.of(result);
		return new VariantEntry(variantName, result.experimentId(), resultFile, summary.passRate(),
				result.items().size(), result.totalCostUsd(), result.totalDurationMs(), result.totalTokens(),
				summary.measures());
	}

	/** Build an entry, deriving its rate from what the run counted. */
	public static VariantEntry of(String variantName, String experimentId, String resultFile,
			@Nullable ItemCounts counts, int itemCount, @Nullable Double costUsd, @Nullable Long durationMs) {
		Double rate = counts == null ? null : counts.passRate().isPresent() ? counts.passRate().getAsDouble() : null;
		return new VariantEntry(variantName, experimentId, resultFile, rate, itemCount, costUsd, durationMs, null,
				null);
	}

}
