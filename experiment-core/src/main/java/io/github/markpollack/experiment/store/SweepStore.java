package io.github.markpollack.experiment.store;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * Persistence abstraction for experiment sweeps. A sweep groups multiple
 * {@link RunSession}s into one logical experiment run with expected variants,
 * last-write-wins resolution, and completeness tracking.
 *
 * <p>
 * Separate from {@link SessionStore} — sweeps reference sessions by name and depend on
 * {@link SessionStore} to load session data for variant resolution.
 * </p>
 */
public interface SweepStore {

	/**
	 * Create a new sweep with initial status {@link SweepStatus#RUNNING} and all variants
	 * unresolved.
	 * @param sweepName human-readable sweep name
	 * @param experimentName the experiment this sweep belongs to
	 * @param expectedVariants variant names expected in this sweep (must not be empty)
	 * @param metadata arbitrary key-value pairs (model, dataset version, etc.)
	 * @return the created sweep
	 * @throws IllegalArgumentException if expectedVariants is empty
	 * @throws ResultStoreException if the sweep cannot be created
	 */
	Sweep createSweep(String sweepName, String experimentName, List<String> expectedVariants,
			Map<String, String> metadata);

	/**
	 * Add a session to the sweep. Loads the session via {@link SessionStore}, matches its
	 * variants against expected variants, and updates resolutions (last-write-wins).
	 * Appends the session name to {@link Sweep#sessionHistory()}. Auto-transitions status
	 * from {@link SweepStatus#RUNNING} to {@link SweepStatus#PARTIAL} when at least one
	 * variant is resolved. Session variants not in the expected list are silently
	 * ignored.
	 * @param sweepName the sweep name
	 * @param experimentName the experiment name
	 * @param sessionName the session to add
	 * @param gitCommit the git commit at which the session ran (null if unknown)
	 * @throws ResultStoreException if the sweep or session cannot be loaded
	 */
	void addSession(String sweepName, String experimentName, String sessionName, @Nullable String gitCommit);

	/**
	 * Remove a session from the sweep. Clears any variant resolutions that point to this
	 * session, reverting them to unresolved. The session name remains in
	 * {@link Sweep#sessionHistory()} (append-only audit trail).
	 * @param sweepName the sweep name
	 * @param experimentName the experiment name
	 * @param sessionName the session to remove
	 * @throws ResultStoreException if the sweep cannot be loaded
	 */
	void removeSession(String sweepName, String experimentName, String sessionName);

	/**
	 * Finalize a sweep by setting its status and completion timestamp.
	 * @param sweepName the sweep name
	 * @param experimentName the experiment name
	 * @param status the final status (typically COMPLETED or FAILED)
	 * @throws ResultStoreException if the sweep cannot be finalized
	 */
	void finalizeSweep(String sweepName, String experimentName, SweepStatus status);

	/**
	 * Load a sweep by experiment name and sweep name.
	 * @param experimentName the experiment name
	 * @param sweepName the sweep name
	 * @return the sweep, or empty if not found
	 * @throws ResultStoreException if the sweep cannot be loaded
	 */
	Optional<Sweep> loadSweep(String experimentName, String sweepName);

	/**
	 * List all sweeps for a given experiment, ordered by creation time (oldest first).
	 * @param experimentName the experiment name
	 * @return sweeps for the experiment, empty list if none found
	 * @throws ResultStoreException if sweeps cannot be listed
	 */
	List<Sweep> listSweeps(String experimentName);

}
