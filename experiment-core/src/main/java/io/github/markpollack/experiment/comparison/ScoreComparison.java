package io.github.markpollack.experiment.comparison;

/**
 * Per-scorer summary across all items in a comparison.
 *
 * @param scorerName judge/scorer name
 * @param currentMean mean score in current experiment
 * @param baselineMean mean score in baseline
 * @param delta currentMean - baselineMean
 * @param improvements items where current > baseline
 * @param regressions items where current < baseline
 * @param unchanged items where current == baseline
 * @param newItems items in current but not baseline
 * @param removedItems items in baseline but not current
 */
public record ScoreComparison(String scorerName, double currentMean, double baselineMean, double delta,
		int improvements, int regressions, int unchanged, int newItems, int removedItems) {

	public ScoreComparison {
		java.util.Objects.requireNonNull(scorerName, "scorerName must not be null");
	}

}
