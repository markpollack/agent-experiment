package io.github.markpollack.experiment.dataset;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.github.markpollack.experiment.util.GitOperations;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Filesystem-based {@link DatasetManager} implementation. Loads datasets from local
 * directories containing {@code dataset.json} manifests and {@code items/} directories.
 */
public class FileSystemDatasetManager implements DatasetManager {

	private static final Logger logger = LoggerFactory.getLogger(FileSystemDatasetManager.class);

	private static final int SUPPORTED_SCHEMA_VERSION = 1;

	private final ObjectMapper objectMapper;

	public FileSystemDatasetManager(ObjectMapper objectMapper) {
		this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper must not be null");
	}

	public FileSystemDatasetManager() {
		this(new ObjectMapper());
	}

	@Override
	public Dataset load(Path datasetDir) {
		java.util.Objects.requireNonNull(datasetDir, "datasetDir must not be null");

		Path manifestPath = datasetDir.resolve("dataset.json");
		if (!Files.isRegularFile(manifestPath)) {
			throw new DatasetLoadException("dataset.json not found in " + datasetDir);
		}

		try {
			JsonNode root = objectMapper.readTree(manifestPath.toFile());

			int schemaVersion = root.path("schemaVersion").asInt(0);
			if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
				throw new DatasetLoadException(
						"Unsupported schema version " + schemaVersion + " (expected " + SUPPORTED_SCHEMA_VERSION + ")");
			}

			String name = requireField(root, "name", manifestPath);
			String description = root.path("description").asText("");
			String declaredVersion = requireField(root, "version", manifestPath);

			Map<String, Object> metadata = Map.of();
			if (root.has("metadata") && root.get("metadata").isObject()) {
				metadata = objectMapper.convertValue(root.get("metadata"), Map.class);
			}

			List<DatasetItemEntry> itemEntries = parseItemEntries(root, manifestPath);

			logger.debug("Loaded dataset '{}' v{} with {} items from {}", name, declaredVersion, itemEntries.size(),
					datasetDir);

			return new Dataset(name, description, schemaVersion, declaredVersion, datasetDir.toAbsolutePath(), metadata,
					itemEntries);
		}
		catch (DatasetLoadException ex) {
			throw ex;
		}
		catch (IOException ex) {
			throw new DatasetLoadException("Failed to read dataset.json from " + datasetDir, ex);
		}
	}

	@Override
	public List<DatasetItem> activeItems(Dataset dataset) {
		return filteredItems(dataset, ItemFilter.all());
	}

	@Override
	public List<DatasetItem> filteredItems(Dataset dataset, ItemFilter filter) {
		java.util.Objects.requireNonNull(dataset, "dataset must not be null");
		java.util.Objects.requireNonNull(filter, "filter must not be null");

		// Two-pass filter: manifest fields first (cheap), then item.json fields after
		// loading (tags, noChange)
		return dataset.itemEntries()
			.stream()
			.filter(entry -> matchesManifestFilter(entry, filter))
			.map(entry -> loadItem(dataset.rootDir(), entry))
			.filter(item -> matchesItemFilter(item, filter))
			.toList();
	}

	@Override
	public DatasetVersion currentVersion(Dataset dataset) {
		java.util.Objects.requireNonNull(dataset, "dataset must not be null");

		List<DatasetItem> activeItems = activeItems(dataset);
		String gitCommit = GitOperations.resolveHead(dataset.rootDir());
		boolean dirty = GitOperations.isDirty(dataset.rootDir());

		return new DatasetVersion(dataset.declaredVersion(), gitCommit, dirty, activeItems.size());
	}

	@Override
	public ResolvedItem resolve(DatasetItem item) {
		java.util.Objects.requireNonNull(item, "item must not be null");

		Path itemDir = item.itemDir();
		Path beforeDir = (item.beforeRef() != null) ? null : itemDir.resolve("before");
		Path referenceDir = (item.referenceRef() != null) ? null : itemDir.resolve("reference");

		return new ResolvedItem(item, beforeDir, referenceDir, itemDir.resolve("item.json"), item.beforeRef(),
				item.referenceRef());
	}

	private List<DatasetItemEntry> parseItemEntries(JsonNode root, Path manifestPath) {
		JsonNode itemsNode = root.get("items");
		if (itemsNode == null || !itemsNode.isArray()) {
			throw new DatasetLoadException("Missing or invalid 'items' array in " + manifestPath);
		}

		var entries = new java.util.ArrayList<DatasetItemEntry>();
		for (JsonNode itemNode : itemsNode) {
			String id = requireField(itemNode, "id", manifestPath);
			String slug = itemNode.path("slug").asText(id);
			String path = requireField(itemNode, "path", manifestPath);
			String bucket = itemNode.path("bucket").asText("");
			String taskType = itemNode.path("taskType").asText(itemNode.path("recipe").asText(""));
			String status = itemNode.path("status").asText("active");

			entries.add(new DatasetItemEntry(id, slug, path, bucket, taskType, status));
		}
		return entries;
	}

	private boolean matchesManifestFilter(DatasetItemEntry entry, ItemFilter filter) {
		// Default: exclude disabled/deprecated items unless filter explicitly sets a
		// status
		if (filter.status() == null && ("disabled".equals(entry.status()) || "deprecated".equals(entry.status()))) {
			return false;
		}
		if (filter.status() != null && !filter.status().equals(entry.status())) {
			return false;
		}
		if (filter.bucket() != null && !filter.bucket().equals(entry.bucket())) {
			return false;
		}
		if (filter.taskType() != null && !filter.taskType().equals(entry.taskType())) {
			return false;
		}
		return true;
	}

	private boolean matchesItemFilter(DatasetItem item, ItemFilter filter) {
		if (filter.noChange() != null && filter.noChange() != item.noChange()) {
			return false;
		}
		if (filter.tags() != null && !item.tags().containsAll(filter.tags())) {
			return false;
		}
		return true;
	}

	DatasetItem loadItem(Path datasetRoot, DatasetItemEntry entry) {
		Path itemDir = datasetRoot.resolve(entry.path()).toAbsolutePath();
		Path itemJsonPath = itemDir.resolve("item.json");

		if (!Files.isRegularFile(itemJsonPath)) {
			throw new DatasetLoadException("item.json not found at " + itemJsonPath);
		}

		try {
			JsonNode root = objectMapper.readTree(itemJsonPath.toFile());

			String developerTask = root.path("developerTask").asText("");
			boolean noChange = root.path("noChange").asBoolean(false);

			List<String> knowledgeRefs = List.of();
			if (root.has("knowledgeRefs") && root.get("knowledgeRefs").isArray()) {
				var refs = new java.util.ArrayList<String>();
				for (JsonNode ref : root.get("knowledgeRefs")) {
					refs.add(ref.asText());
				}
				knowledgeRefs = refs;
			}

			List<String> tags = List.of();
			if (root.has("tags") && root.get("tags").isArray()) {
				var tagList = new java.util.ArrayList<String>();
				for (JsonNode tag : root.get("tags")) {
					tagList.add(tag.asText());
				}
				tags = tagList;
			}

			SourceRef beforeRef = parseSourceRef(root, "beforeRef", itemJsonPath);
			SourceRef referenceRef = parseSourceRef(root, "referenceRef", itemJsonPath);

			return new DatasetItem(entry.id(), entry.slug(), developerTask, entry.taskType(), entry.bucket(), noChange,
					knowledgeRefs, tags, entry.status(), itemDir, beforeRef, referenceRef);
		}
		catch (IOException ex) {
			throw new DatasetLoadException("Failed to read item.json at " + itemJsonPath, ex);
		}
	}

	@Nullable
	private SourceRef parseSourceRef(JsonNode root, String fieldName, Path source) {
		JsonNode refNode = root.get(fieldName);
		if (refNode == null || refNode.isNull() || !refNode.isObject()) {
			return null;
		}

		String repoPath = refNode.path("repoPath").asText(null);
		String commitHash = refNode.path("commitHash").asText(null);
		if (repoPath == null || commitHash == null) {
			throw new DatasetLoadException(
					"'" + fieldName + "' requires 'repoPath' and 'commitHash' fields in " + source);
		}

		String subDirectory = refNode.has("subDirectory") && !refNode.get("subDirectory").isNull()
				? refNode.get("subDirectory").asText() : null;

		try {
			return (subDirectory != null) ? SourceRef.of(Path.of(repoPath), commitHash, subDirectory)
					: SourceRef.of(Path.of(repoPath), commitHash);
		}
		catch (IllegalArgumentException ex) {
			throw new DatasetLoadException("Invalid '" + fieldName + "' in " + source + ": " + ex.getMessage(), ex);
		}
	}

	private String requireField(JsonNode node, String field, Path source) {
		JsonNode value = node.get(field);
		if (value == null || value.isNull() || value.asText().isEmpty()) {
			throw new DatasetLoadException("Missing required field '" + field + "' in " + source);
		}
		return value.asText();
	}

}
