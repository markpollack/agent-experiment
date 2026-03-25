package io.github.markpollack.experiment.store;

import java.time.Instant;
import java.util.List;

/**
 * Index of sweeps for a single experiment. Stored as
 * {@code {experimentName}/sweeps-index.json}.
 *
 * @param experimentName the experiment name this index covers
 * @param entries index entries, one per sweep
 */
record SweepIndex(String experimentName, List<SweepIndexEntry> entries) {

	SweepIndex {
		entries = List.copyOf(entries);
	}

	/**
	 * Single entry in the sweep index.
	 *
	 * @param sweepName the human-readable sweep name
	 * @param createdAt when the sweep was created
	 * @param status current sweep status
	 * @param resolvedCount number of resolved variants
	 * @param expectedCount total number of expected variants
	 * @param path relative path to the sweep directory
	 */
	record SweepIndexEntry(String sweepName, Instant createdAt, SweepStatus status, int resolvedCount,
			int expectedCount, String path) {
	}

}
