package io.github.markpollack.experiment.store;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * Tracks the resolution of an expected variant within a {@link Sweep}. Each resolution
 * maps a variant name to the session that provided results for it, with an optional git
 * commit for version mismatch detection.
 *
 * @param variantName the expected variant name (e.g., "control", "variant-a")
 * @param sessionName the session that resolved this variant (null if unresolved)
 * @param resolvedAt when this variant was resolved (null if unresolved)
 * @param gitCommit the git commit at which the resolving session ran (null if unknown)
 */
public record SweepVariantResolution(String variantName, @Nullable String sessionName, @Nullable Instant resolvedAt,
		@Nullable String gitCommit) {

	/**
	 * Create an unresolved variant resolution.
	 * @param variantName the expected variant name
	 * @return an unresolved resolution
	 */
	public static SweepVariantResolution unresolved(String variantName) {
		return new SweepVariantResolution(variantName, null, null, null);
	}

	/**
	 * Whether this variant has been resolved by a session.
	 * @return true if a session has provided results for this variant
	 */
	public boolean isResolved() {
		return sessionName != null;
	}

}
