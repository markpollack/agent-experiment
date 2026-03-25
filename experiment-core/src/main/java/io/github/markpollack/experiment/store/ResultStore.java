package io.github.markpollack.experiment.store;

import java.util.List;
import java.util.Optional;

import io.github.markpollack.experiment.result.ExperimentResult;

/**
 * Persistence abstraction for experiment results. Implementations store and retrieve
 * {@link ExperimentResult} instances for cross-run comparison and audit.
 */
public interface ResultStore {

	/**
	 * Persist an experiment result.
	 * @param result the result to save
	 * @throws ResultStoreException if the result cannot be saved
	 */
	void save(ExperimentResult result);

	/**
	 * Load an experiment result by its unique ID.
	 * @param id the experiment ID
	 * @return the result, or empty if not found
	 * @throws ResultStoreException if the result cannot be loaded
	 */
	Optional<ExperimentResult> load(String id);

	/**
	 * List all results for a given experiment name, ordered by timestamp (oldest first).
	 * @param experimentName the experiment name
	 * @return results for the experiment, empty list if none found
	 * @throws ResultStoreException if results cannot be listed
	 */
	List<ExperimentResult> listByName(String experimentName);

	/**
	 * Get the most recent result for a given experiment name.
	 * @param experimentName the experiment name
	 * @return the most recent result, or empty if none found
	 * @throws ResultStoreException if the result cannot be loaded
	 */
	Optional<ExperimentResult> mostRecent(String experimentName);

}
