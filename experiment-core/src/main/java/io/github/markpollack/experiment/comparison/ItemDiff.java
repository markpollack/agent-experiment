package io.github.markpollack.experiment.comparison;

import java.util.Map;

/**
 * Per-item comparison between two experiment runs.
 *
 * @param itemId fixture ID (stable across runs)
 * @param currentScores scores in the current experiment
 * @param baselineScores scores in the baseline experiment
 * @param scoreDeltas per-scorer delta (current - baseline)
 * @param status overall item status
 */
public record ItemDiff(String itemId, Map<String, Double> currentScores, Map<String, Double> baselineScores,
		Map<String, Double> scoreDeltas, DiffStatus status) {

	public ItemDiff {
		java.util.Objects.requireNonNull(itemId, "itemId must not be null");
		java.util.Objects.requireNonNull(status, "status must not be null");
		currentScores = Map.copyOf(currentScores);
		baselineScores = Map.copyOf(baselineScores);
		scoreDeltas = Map.copyOf(scoreDeltas);
	}

}
