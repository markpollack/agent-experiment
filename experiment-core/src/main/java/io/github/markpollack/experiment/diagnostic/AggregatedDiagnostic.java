package io.github.markpollack.experiment.diagnostic;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cross-run diagnostic aggregation identifying systemic patterns and stochasticity.
 *
 * @param runCount number of experiment runs analyzed
 * @param overallDistribution gap distribution aggregated across all runs
 * @param perRunDistributions per-run gap distributions keyed by experiment ID
 * @param stochasticItems item IDs where the same item received different dominant gap
 * categories across runs (indicates STOCHASTICITY_GAP)
 * @param stableItems item IDs where the dominant gap category was consistent across all
 * runs
 * @param stabilityFraction fraction of items that are stable (0.0-1.0)
 * @param recommendations cross-run recommendations
 */
public record AggregatedDiagnostic(int runCount, GapDistribution overallDistribution,
		Map<String, GapDistribution> perRunDistributions, Set<String> stochasticItems, Set<String> stableItems,
		double stabilityFraction, List<String> recommendations) {

	public AggregatedDiagnostic {
		perRunDistributions = Map.copyOf(perRunDistributions);
		stochasticItems = Set.copyOf(stochasticItems);
		stableItems = Set.copyOf(stableItems);
		recommendations = List.copyOf(recommendations);
	}

}
