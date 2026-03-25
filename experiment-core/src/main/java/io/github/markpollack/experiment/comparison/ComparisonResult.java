package io.github.markpollack.experiment.comparison;

import java.util.List;
import java.util.Map;

/**
 * Result of comparing two experiment runs.
 *
 * @param currentExperimentId current experiment ID
 * @param baselineExperimentId baseline experiment ID
 * @param scoreComparisons per-scorer summary
 * @param itemDiffs per-item detail
 * @param summary aggregate statistics for the current experiment
 */
public record ComparisonResult(String currentExperimentId, String baselineExperimentId,
		Map<String, ScoreComparison> scoreComparisons, List<ItemDiff> itemDiffs, ExperimentSummary summary) {

	public ComparisonResult {
		java.util.Objects.requireNonNull(currentExperimentId, "currentExperimentId must not be null");
		java.util.Objects.requireNonNull(baselineExperimentId, "baselineExperimentId must not be null");
		scoreComparisons = Map.copyOf(scoreComparisons);
		itemDiffs = List.copyOf(itemDiffs);
		java.util.Objects.requireNonNull(summary, "summary must not be null");
	}

}
