package io.github.markpollack.experiment.store;

import java.time.Instant;
import java.util.List;

/**
 * Index of sessions for a single experiment. Stored as
 * {@code {experimentName}/sessions-index.json}.
 *
 * @param experimentName the experiment name this index covers
 * @param entries index entries, one per session
 */
record SessionIndex(String experimentName, List<SessionIndexEntry> entries) {

	SessionIndex {
		entries = List.copyOf(entries);
	}

	/**
	 * Single entry in the session index.
	 *
	 * @param sessionName the human-readable session name
	 * @param createdAt when the session was created
	 * @param status current session status
	 * @param variantCount number of variants in the session
	 * @param path relative path to the session directory
	 */
	record SessionIndexEntry(String sessionName, Instant createdAt, RunSessionStatus status, int variantCount,
			String path) {
	}

}
