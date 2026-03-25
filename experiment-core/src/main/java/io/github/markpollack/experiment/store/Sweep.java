package io.github.markpollack.experiment.store;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

/**
 * Groups multiple {@link RunSession}s into one logical experiment run with expected
 * variants, last-write-wins resolution, and completeness tracking.
 *
 * <p>
 * A Sweep defines which variants are expected and tracks which sessions have provided
 * results for each variant. When a new session resolves an already-resolved variant, the
 * new session wins (last-write-wins). The {@link #sessionHistory()} provides an
 * append-only audit trail of all sessions that have been added, even if later replaced.
 * </p>
 *
 * @param sweepName human-readable sweep name (e.g., "stage5-full")
 * @param experimentName the experiment this sweep belongs to
 * @param createdAt when the sweep was created
 * @param completedAt when the sweep was finalized (null while running)
 * @param status current sweep status
 * @param expectedVariants the list of variant names expected in this sweep
 * @param resolutions per-variant resolution tracking
 * @param sessionHistory append-only log of all sessions added to this sweep
 * @param metadata arbitrary key-value pairs (model, dataset version, etc.)
 */
public record Sweep(String sweepName, String experimentName, Instant createdAt, @Nullable Instant completedAt,
		SweepStatus status, List<String> expectedVariants, List<SweepVariantResolution> resolutions,
		List<String> sessionHistory, Map<String, String> metadata) {

	public Sweep {
		expectedVariants = List.copyOf(expectedVariants);
		resolutions = List.copyOf(resolutions);
		sessionHistory = List.copyOf(sessionHistory);
		metadata = Map.copyOf(metadata);
	}

	/**
	 * Returns the variant names that have not yet been resolved by any session.
	 * @return unresolved variant names
	 */
	public List<String> missingVariants() {
		Set<String> resolved = resolutions.stream()
			.filter(SweepVariantResolution::isResolved)
			.map(SweepVariantResolution::variantName)
			.collect(Collectors.toSet());
		return expectedVariants.stream().filter(v -> !resolved.contains(v)).toList();
	}

	/**
	 * Whether all expected variants have been resolved.
	 * @return true if every expected variant has a resolution
	 */
	public boolean isComplete() {
		return missingVariants().isEmpty();
	}

	/**
	 * Whether resolved variants were run at different git commits. Useful for detecting
	 * when constituent sessions used different code versions.
	 * @return true if two or more resolved variants have different non-null git commits
	 */
	public boolean hasVersionMismatch() {
		Set<String> commits = resolutions.stream()
			.filter(SweepVariantResolution::isResolved)
			.map(SweepVariantResolution::gitCommit)
			.filter(c -> c != null)
			.collect(Collectors.toSet());
		return commits.size() > 1;
	}

}
