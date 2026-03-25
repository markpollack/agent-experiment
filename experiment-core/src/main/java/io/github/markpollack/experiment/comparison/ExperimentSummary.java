package io.github.markpollack.experiment.comparison;

import java.util.Map;

/**
 * Summary statistics for a single experiment run.
 *
 * @param experimentId experiment ID
 * @param experimentName experiment name
 * @param totalItems number of items evaluated
 * @param passRate fraction of items that passed (0.0-1.0)
 * @param totalCostUsd sum of cost across all items
 * @param totalTokens sum of tokens across all items
 * @param totalDurationMs wall-clock duration
 * @param scoreAggregates per-judge mean score
 */
public record ExperimentSummary(String experimentId, String experimentName, int totalItems, double passRate,
		double totalCostUsd, int totalTokens, long totalDurationMs, Map<String, Double> scoreAggregates) {

	public ExperimentSummary {
		java.util.Objects.requireNonNull(experimentId, "experimentId must not be null");
		java.util.Objects.requireNonNull(experimentName, "experimentName must not be null");
		scoreAggregates = Map.copyOf(scoreAggregates);
	}

}
