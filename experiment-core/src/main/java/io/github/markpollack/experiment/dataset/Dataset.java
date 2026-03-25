package io.github.markpollack.experiment.dataset;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A loaded dataset with metadata and item index. Constructed by
 * {@link DatasetManager#load(Path)} from a {@code dataset.json} manifest file.
 *
 * @param name dataset name (matches directory name)
 * @param description human-readable description
 * @param schemaVersion manifest schema version (currently 1)
 * @param declaredVersion human-managed semantic version from dataset.json
 * @param rootDir absolute path to dataset directory
 * @param metadata dataset-level metadata (source, extraction tool, etc.)
 * @param itemEntries item index from the manifest
 */
public record Dataset(String name, String description, int schemaVersion, String declaredVersion, Path rootDir,
		Map<String, Object> metadata, List<DatasetItemEntry> itemEntries) {

	public Dataset {
		java.util.Objects.requireNonNull(name, "name must not be null");
		java.util.Objects.requireNonNull(description, "description must not be null");
		java.util.Objects.requireNonNull(declaredVersion, "declaredVersion must not be null");
		java.util.Objects.requireNonNull(rootDir, "rootDir must not be null");
		metadata = Map.copyOf(metadata);
		itemEntries = List.copyOf(itemEntries);
	}

}
