package io.github.markpollack.experiment.dataset;

import java.nio.file.Path;
import java.util.List;

/**
 * Manages Git-managed fixture datasets. Datasets are directories containing a
 * {@code dataset.json} manifest and an {@code items/} directory with individual test case
 * fixtures.
 */
public interface DatasetManager {

	/**
	 * Load a dataset from the filesystem.
	 * @param datasetDir absolute path to dataset directory (contains dataset.json)
	 * @return loaded dataset with item index
	 * @throws DatasetLoadException if dataset.json is missing, malformed, or
	 * schemaVersion is unsupported
	 */
	Dataset load(Path datasetDir);

	/**
	 * List all active items in a dataset (excludes disabled/deprecated items). Each item
	 * is fully loaded from its {@code item.json}.
	 * @param dataset loaded dataset
	 * @return list of active dataset items
	 */
	List<DatasetItem> activeItems(Dataset dataset);

	/**
	 * List all items matching the given filter criteria. Items with
	 * {@code status == "disabled"} are excluded unless the filter explicitly sets a
	 * status.
	 * @param dataset loaded dataset
	 * @param filter filter for bucket, tags, status, taskType, etc.
	 * @return filtered list of dataset items
	 */
	List<DatasetItem> filteredItems(Dataset dataset, ItemFilter filter);

	/**
	 * Compute the current version identity of a dataset. Returns the declared semantic
	 * version, git commit SHA, dirty flag, and active item count.
	 * @param dataset loaded dataset
	 * @return version descriptor
	 */
	DatasetVersion currentVersion(Dataset dataset);

	/**
	 * Resolve a dataset item's before/after file paths.
	 * @param item dataset item
	 * @return resolved absolute paths for the item's fixture files
	 */
	ResolvedItem resolve(DatasetItem item);

}
