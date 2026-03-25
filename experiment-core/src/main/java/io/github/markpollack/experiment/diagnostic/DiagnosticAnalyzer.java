package io.github.markpollack.experiment.diagnostic;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import io.github.markpollack.experiment.pipeline.AnalysisEnvelope;
import io.github.markpollack.experiment.pipeline.ExecutionPlan;
import io.github.markpollack.experiment.result.ExperimentResult;
import io.github.markpollack.experiment.result.ItemResult;
import org.jspecify.annotations.Nullable;

/**
 * Analyzes an experiment result to produce a structured diagnostic report with gap
 * classification.
 *
 * <p>
 * For each item in the experiment, extracts the verdict, runs the gap classifier, and
 * identifies the dominant gap category. Aggregates gap distributions and generates
 * actionable recommendations.
 */
public class DiagnosticAnalyzer {

	private final GapClassifier classifier;

	public DiagnosticAnalyzer(GapClassifier classifier) {
		this.classifier = classifier;
	}

	/**
	 * Analyze an experiment result into a diagnostic report.
	 * @param result the experiment result to analyze
	 * @return a diagnostic report with per-item gap classifications and recommendations
	 */
	public DiagnosticReport analyze(ExperimentResult result) {
		List<ItemDiagnostic> itemDiagnostics = new ArrayList<>();
		List<DiagnosticCheck> allChecks = new ArrayList<>();

		for (ItemResult item : result.items()) {
			if (item.verdict() == null) {
				// No verdict — item failed before judging
				itemDiagnostics.add(new ItemDiagnostic(item.itemId(), List.of(), null));
				continue;
			}

			// Extract analysis and plan from invocation result if available
			AnalysisEnvelope analysis = null;
			ExecutionPlan plan = null;
			if (item.invocationResult() != null) {
				analysis = item.invocationResult().analysis();
				plan = item.invocationResult().executionPlan();
			}

			List<DiagnosticCheck> checks = classifier.classify(item.verdict(), analysis, plan);
			allChecks.addAll(checks);

			GapCategory dominant = dominantGap(checks);
			itemDiagnostics.add(new ItemDiagnostic(item.itemId(), checks, dominant));
		}

		GapDistribution distribution = GapDistribution.fromChecks(allChecks);
		List<String> recommendations = generateRecommendations(distribution);

		return new DiagnosticReport(result.experimentId(), itemDiagnostics, distribution, recommendations);
	}

	@Nullable
	private GapCategory dominantGap(List<DiagnosticCheck> checks) {
		Map<GapCategory, Integer> counts = new EnumMap<>(GapCategory.class);
		for (DiagnosticCheck dc : checks) {
			if (dc.gapCategory() != null) {
				counts.merge(dc.gapCategory(), 1, Integer::sum);
			}
		}
		return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
	}

	private List<String> generateRecommendations(GapDistribution distribution) {
		List<String> recommendations = new ArrayList<>();

		if (distribution.totalChecks() == 0) {
			recommendations.add("No failures detected — all judges passed.");
			return recommendations;
		}

		// Recommend based on dominant gap
		if (distribution.dominant() != null) {
			GapCategory dominant = distribution.dominant();
			double fraction = distribution.fractions().getOrDefault(dominant, 0.0);
			int percentage = (int) (fraction * 100);
			recommendations
				.add(percentage + "% of failures are " + dominant.name() + " — " + dominant.actionableAdvice());
		}

		// Add specific recommendations for high-frequency categories
		for (Map.Entry<GapCategory, Double> entry : distribution.fractions().entrySet()) {
			if (entry.getKey() == distribution.dominant()) {
				continue; // Already covered above
			}
			if (entry.getValue() >= 0.2) {
				int percentage = (int) (entry.getValue() * 100);
				recommendations.add(percentage + "% of failures are " + entry.getKey().name() + " — "
						+ entry.getKey().actionableAdvice());
			}
		}

		return recommendations;
	}

}
