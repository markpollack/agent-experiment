package io.github.markpollack.experiment.comparison;

import java.util.Map;

import io.github.markpollack.experiment.result.ItemCounts;
import org.jspecify.annotations.Nullable;

/**
 * Summary statistics for a single experiment run.
 *
 * <p>
 * The pass rate is derived here, at read time, from the counts the run recorded. It is
 * {@code null} when the jury scored nothing, and when the run recorded no counts at all —
 * two different absences, neither of which is zero.
 *
 * @param experimentId experiment ID
 * @param experimentName experiment name
 * @param totalItems number of items evaluated
 * @param counts what the run observed, counted; null when it recorded none
 * @param passRate passes over items the jury scored, or null when there is no rate
 * @param totalCostUsd sum of cost across all items
 * @param totalTokens sum of tokens across all items
 * @param totalDurationMs wall-clock duration
 * @param scoreAggregates per-judge mean score
 */
public record ExperimentSummary(String experimentId, String experimentName, int totalItems, @Nullable ItemCounts counts,
		@Nullable Double passRate, double totalCostUsd, int totalTokens, long totalDurationMs,
		Map<String, Double> scoreAggregates) {

	public ExperimentSummary {
		java.util.Objects.requireNonNull(experimentId, "experimentId must not be null");
		java.util.Objects.requireNonNull(experimentName, "experimentName must not be null");
		scoreAggregates = Map.copyOf(scoreAggregates);
	}

	/** Summarize a run, deriving its rate from what it counted. */
	public static ExperimentSummary of(String experimentId, String experimentName, int totalItems,
			@Nullable ItemCounts counts, double totalCostUsd, int totalTokens, long totalDurationMs,
			Map<String, Double> scoreAggregates) {
		Double rate = counts == null ? null : counts.passRate().isPresent() ? counts.passRate().getAsDouble() : null;
		return new ExperimentSummary(experimentId, experimentName, totalItems, counts, rate, totalCostUsd, totalTokens,
				totalDurationMs, scoreAggregates);
	}

}
