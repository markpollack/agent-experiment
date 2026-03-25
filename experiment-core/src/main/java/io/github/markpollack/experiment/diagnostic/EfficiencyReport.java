package io.github.markpollack.experiment.diagnostic;

import java.util.List;
import java.util.Map;

/**
 * The result of efficiency evaluation for a single item: per-metric breakdown, prefixed
 * score map, and weighted composite.
 *
 * <p>
 * All score keys are prefixed with {@code efficiency.} to distinguish them from jury
 * scores in {@code ItemResult.scores()}.
 *
 * @param checks per-metric breakdown with raw values and normalized scores
 * @param scores metric name to normalized score, prefixed with {@code efficiency.}
 * @param compositeScore weighted average of all metric scores
 */
public record EfficiencyReport(List<EfficiencyCheck> checks, Map<String, Double> scores, double compositeScore) {

	public EfficiencyReport {
		checks = List.copyOf(checks);
		scores = Map.copyOf(scores);
	}

}
