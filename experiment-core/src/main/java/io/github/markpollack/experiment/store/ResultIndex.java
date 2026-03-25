package io.github.markpollack.experiment.store;

import java.time.Instant;
import java.util.List;

/**
 * Index of experiment results for a single experiment name. Stored as
 * {@code {experimentName}/index.json} alongside the individual result files.
 *
 * @param experimentName the experiment name this index covers
 * @param entries index entries, one per result file
 */
record ResultIndex(String experimentName, List<ResultIndexEntry> entries) {

	ResultIndex {
		entries = List.copyOf(entries);
	}

	/**
	 * Single entry in the result index.
	 *
	 * @param experimentId the unique experiment run ID
	 * @param timestamp when the experiment ran
	 * @param fileName the result file name ({experimentId}.json)
	 */
	record ResultIndexEntry(String experimentId, Instant timestamp, String fileName) {
	}

}
