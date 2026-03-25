package io.github.markpollack.experiment.diagnostic;

import java.util.List;

/**
 * Complete diagnostic report for a single experiment run.
 *
 * @param experimentId the experiment run ID
 * @param items per-item diagnostic analysis
 * @param distribution aggregate gap distribution across all items
 * @param recommendations actionable recommendations derived from the gap distribution
 */
public record DiagnosticReport(String experimentId, List<ItemDiagnostic> items, GapDistribution distribution,
		List<String> recommendations) {

	public DiagnosticReport {
		items = List.copyOf(items);
		recommendations = List.copyOf(recommendations);
	}

}
