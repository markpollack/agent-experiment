package io.github.markpollack.experiment.diagnostic;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Distribution of gap categories across diagnostic checks.
 *
 * @param counts number of checks classified into each gap category
 * @param fractions fraction of total checks in each gap category (0.0-1.0)
 * @param dominant the most frequent gap category (null if no checks)
 * @param totalChecks total number of classified checks (excludes null-category)
 */
public record GapDistribution(Map<GapCategory, Integer> counts, Map<GapCategory, Double> fractions,
		@Nullable GapCategory dominant, int totalChecks) {

	public GapDistribution {
		counts = Map.copyOf(counts);
		fractions = Map.copyOf(fractions);
	}

	/**
	 * Compute a gap distribution from a flat list of diagnostic checks.
	 */
	public static GapDistribution fromChecks(List<DiagnosticCheck> checks) {
		Map<GapCategory, Integer> counts = new EnumMap<>(GapCategory.class);
		int total = 0;

		for (DiagnosticCheck dc : checks) {
			if (dc.gapCategory() != null) {
				counts.merge(dc.gapCategory(), 1, Integer::sum);
				total++;
			}
		}

		Map<GapCategory, Double> fractions = new EnumMap<>(GapCategory.class);
		GapCategory dominant = null;
		int maxCount = 0;

		for (Map.Entry<GapCategory, Integer> entry : counts.entrySet()) {
			fractions.put(entry.getKey(), total > 0 ? (double) entry.getValue() / total : 0.0);
			if (entry.getValue() > maxCount) {
				maxCount = entry.getValue();
				dominant = entry.getKey();
			}
		}

		return new GapDistribution(counts, fractions, dominant, total);
	}

}
