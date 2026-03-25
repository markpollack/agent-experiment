package io.github.markpollack.experiment.diagnostic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aggregates diagnostic reports across multiple experiment runs to identify systemic
 * patterns and stochasticity.
 *
 * <p>
 * Stochasticity detection: if the same item receives different dominant gap categories
 * across runs, it indicates {@link GapCategory#STOCHASTICITY_GAP} — the outcome depends
 * on random factors rather than a fixable root cause.
 */
public class DiagnosticAggregator {

	/**
	 * Aggregate multiple diagnostic reports into a cross-run analysis.
	 * @param reports diagnostic reports from multiple runs (must have at least 1)
	 * @return aggregated diagnostic with stability metrics
	 */
	public AggregatedDiagnostic aggregate(List<DiagnosticReport> reports) {
		if (reports.isEmpty()) {
			return new AggregatedDiagnostic(0, GapDistribution.fromChecks(List.of()), Map.of(), Set.of(), Set.of(), 1.0,
					List.of("No runs to analyze."));
		}

		// Collect all checks across runs for overall distribution
		List<DiagnosticCheck> allChecks = new ArrayList<>();
		Map<String, GapDistribution> perRunDistributions = new LinkedHashMap<>();

		// Track per-item dominant gaps across runs: itemId → set of dominant gaps seen
		Map<String, Set<GapCategory>> itemDominantGaps = new HashMap<>();

		for (DiagnosticReport report : reports) {
			List<DiagnosticCheck> runChecks = new ArrayList<>();
			for (ItemDiagnostic item : report.items()) {
				runChecks.addAll(item.checks());
				if (item.dominantGap() != null) {
					itemDominantGaps.computeIfAbsent(item.itemId(), k -> new HashSet<>()).add(item.dominantGap());
				}
			}
			allChecks.addAll(runChecks);
			perRunDistributions.put(report.experimentId(), GapDistribution.fromChecks(runChecks));
		}

		GapDistribution overallDistribution = GapDistribution.fromChecks(allChecks);

		// Classify items as stable vs stochastic
		Set<String> stochasticItems = new HashSet<>();
		Set<String> stableItems = new HashSet<>();

		for (Map.Entry<String, Set<GapCategory>> entry : itemDominantGaps.entrySet()) {
			if (entry.getValue().size() > 1) {
				stochasticItems.add(entry.getKey());
			}
			else {
				stableItems.add(entry.getKey());
			}
		}

		int totalClassifiedItems = stochasticItems.size() + stableItems.size();
		double stabilityFraction = totalClassifiedItems > 0 ? (double) stableItems.size() / totalClassifiedItems : 1.0;

		List<String> recommendations = generateCrossRunRecommendations(overallDistribution, stochasticItems,
				stabilityFraction, reports.size());

		return new AggregatedDiagnostic(reports.size(), overallDistribution, perRunDistributions, stochasticItems,
				stableItems, stabilityFraction, recommendations);
	}

	private List<String> generateCrossRunRecommendations(GapDistribution distribution, Set<String> stochasticItems,
			double stabilityFraction, int runCount) {
		List<String> recommendations = new ArrayList<>();

		if (runCount < 3) {
			recommendations
				.add("Only " + runCount + " run(s) analyzed — need N>=3 for reliable stochasticity detection.");
		}

		if (!stochasticItems.isEmpty()) {
			recommendations.add(stochasticItems.size() + " item(s) show stochastic behavior (different gap categories "
					+ "across runs) — these failures may not be fixable with deterministic improvements.");
		}

		int stabilityPct = (int) (stabilityFraction * 100);
		recommendations
			.add("Stability: " + stabilityPct + "% of items have consistent failure categories across runs.");

		if (distribution.dominant() != null) {
			GapCategory dominant = distribution.dominant();
			int pct = (int) (distribution.fractions().getOrDefault(dominant, 0.0) * 100);
			recommendations.add("Across all runs, " + pct + "% of failures are " + dominant.name() + " — "
					+ dominant.actionableAdvice());
		}

		return recommendations;
	}

}
