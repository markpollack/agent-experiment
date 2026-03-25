package io.github.markpollack.experiment.store;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.markpollack.experiment.result.ExperimentResult;

/**
 * Persistence abstraction for experiment sessions. Sessions group variant results under a
 * named directory with a manifest ({@code session.json}).
 *
 * <p>
 * Separate from {@link ResultStore} to preserve backward compatibility — existing
 * ResultStore implementations and consumers remain untouched.
 * </p>
 */
public interface SessionStore {

	/**
	 * Create a new session with initial status {@link RunSessionStatus#RUNNING}.
	 * @param sessionName human-readable session name
	 * @param experimentName the experiment this session belongs to
	 * @param metadata arbitrary key-value pairs (model, dataset version, etc.)
	 * @return the created session
	 * @throws ResultStoreException if the session cannot be created
	 */
	RunSession createSession(String sessionName, String experimentName, Map<String, String> metadata);

	/**
	 * Save a variant's result to an existing session. Updates the session manifest with a
	 * new {@link VariantEntry} and writes the full result as {@code {variantName}.json}.
	 * Idempotent — re-saving the same variant replaces the previous entry.
	 * @param sessionName the session name
	 * @param experimentName the experiment name
	 * @param variantName the variant name
	 * @param result the experiment result for this variant
	 * @throws ResultStoreException if the variant cannot be saved
	 */
	void saveVariantToSession(String sessionName, String experimentName, String variantName, ExperimentResult result);

	/**
	 * Finalize a session by setting its status and completion timestamp.
	 * @param sessionName the session name
	 * @param experimentName the experiment name
	 * @param status the final status (typically COMPLETED or FAILED)
	 * @throws ResultStoreException if the session cannot be finalized
	 */
	void finalizeSession(String sessionName, String experimentName, RunSessionStatus status);

	/**
	 * Load a session by experiment name and session name.
	 * @param experimentName the experiment name
	 * @param sessionName the session name
	 * @return the session, or empty if not found
	 * @throws ResultStoreException if the session cannot be loaded
	 */
	Optional<RunSession> loadSession(String experimentName, String sessionName);

	/**
	 * List all sessions for a given experiment, ordered by creation time (oldest first).
	 * @param experimentName the experiment name
	 * @return sessions for the experiment, empty list if none found
	 * @throws ResultStoreException if sessions cannot be listed
	 */
	List<RunSession> listSessions(String experimentName);

	/**
	 * Get the most recently created session for a given experiment.
	 * @param experimentName the experiment name
	 * @return the most recent session, or empty if none found
	 * @throws ResultStoreException if the session cannot be loaded
	 */
	Optional<RunSession> mostRecentSession(String experimentName);

}
