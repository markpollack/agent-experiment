package io.github.markpollack.experiment.store;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import io.github.markpollack.experiment.result.ExperimentResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Filesystem-backed {@link ResultStore} that persists experiment results as JSON files.
 *
 * <p>
 * Layout: {@code {baseDir}/{experimentName}/{experimentId}.json} with an
 * {@code index.json} per experiment name for fast listing.
 * </p>
 *
 * <p>
 * Writes are atomic (temp file + {@link Files#move}) to prevent partial files on crashes.
 * The {@code load(String id)} method scans all subdirectories to find a result by ID
 * without requiring the experiment name.
 * </p>
 */
public class FileSystemResultStore implements ResultStore {

	private static final Logger logger = LoggerFactory.getLogger(FileSystemResultStore.class);

	private static final String INDEX_FILE = "index.json";

	private final Path baseDir;

	private final ObjectMapper objectMapper;

	public FileSystemResultStore(Path baseDir) {
		this(baseDir, ResultObjectMapper.create());
	}

	FileSystemResultStore(Path baseDir, ObjectMapper objectMapper) {
		this.baseDir = java.util.Objects.requireNonNull(baseDir, "baseDir must not be null");
		this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper must not be null");
	}

	@Override
	public void save(ExperimentResult result) {
		Path experimentDir = baseDir.resolve(result.experimentName());
		Path resultFile = experimentDir.resolve(result.experimentId() + ".json");
		Path indexFile = experimentDir.resolve(INDEX_FILE);

		try {
			Files.createDirectories(experimentDir);

			// Atomic write: temp file + move
			Path tempFile = Files.createTempFile(experimentDir, ".result-", ".tmp");
			try {
				objectMapper.writeValue(tempFile.toFile(), result);
				Files.move(tempFile, resultFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			}
			catch (IOException ex) {
				Files.deleteIfExists(tempFile);
				throw ex;
			}

			// Update index
			updateIndex(indexFile, result);

			logger.debug("Saved result {} to {}", result.experimentId(), resultFile);
		}
		catch (IOException ex) {
			throw new ResultStoreException("Failed to save result " + result.experimentId() + " to " + resultFile, ex);
		}
	}

	@Override
	public Optional<ExperimentResult> load(String id) {
		// First try to find via index files for efficiency
		if (!Files.isDirectory(baseDir)) {
			return Optional.empty();
		}
		try (DirectoryStream<Path> dirs = Files.newDirectoryStream(baseDir, Files::isDirectory)) {
			for (Path experimentDir : dirs) {
				Path resultFile = experimentDir.resolve(id + ".json");
				if (Files.isRegularFile(resultFile)) {
					ExperimentResult result = objectMapper.readValue(resultFile.toFile(), ExperimentResult.class);
					return Optional.of(result);
				}
			}
		}
		catch (IOException ex) {
			throw new ResultStoreException("Failed to load result " + id, ex);
		}
		return Optional.empty();
	}

	@Override
	public List<ExperimentResult> listByName(String experimentName) {
		Path experimentDir = baseDir.resolve(experimentName);
		Path indexFile = experimentDir.resolve(INDEX_FILE);

		if (!Files.isRegularFile(indexFile)) {
			return List.of();
		}

		try {
			ResultIndex index = objectMapper.readValue(indexFile.toFile(), ResultIndex.class);
			List<ExperimentResult> results = new ArrayList<>();
			for (ResultIndex.ResultIndexEntry entry : index.entries()) {
				Path resultFile = experimentDir.resolve(entry.fileName());
				if (Files.isRegularFile(resultFile)) {
					results.add(objectMapper.readValue(resultFile.toFile(), ExperimentResult.class));
				}
				else {
					logger.warn("Index references missing file: {}", resultFile);
				}
			}
			results.sort(Comparator.comparing(ExperimentResult::timestamp));
			return List.copyOf(results);
		}
		catch (IOException ex) {
			throw new ResultStoreException("Failed to list results for " + experimentName, ex);
		}
	}

	@Override
	public Optional<ExperimentResult> mostRecent(String experimentName) {
		Path experimentDir = baseDir.resolve(experimentName);
		Path indexFile = experimentDir.resolve(INDEX_FILE);

		if (!Files.isRegularFile(indexFile)) {
			return Optional.empty();
		}

		try {
			ResultIndex index = objectMapper.readValue(indexFile.toFile(), ResultIndex.class);
			if (index.entries().isEmpty()) {
				return Optional.empty();
			}
			// Find latest by timestamp from index — avoids loading all result files
			ResultIndex.ResultIndexEntry latest = index.entries()
				.stream()
				.max(Comparator.comparing(ResultIndex.ResultIndexEntry::timestamp))
				.orElseThrow();
			Path resultFile = experimentDir.resolve(latest.fileName());
			if (!Files.isRegularFile(resultFile)) {
				logger.warn("Index references missing file: {}", resultFile);
				return Optional.empty();
			}
			return Optional.of(objectMapper.readValue(resultFile.toFile(), ExperimentResult.class));
		}
		catch (IOException ex) {
			throw new ResultStoreException("Failed to load most recent result for " + experimentName, ex);
		}
	}

	private void updateIndex(Path indexFile, ExperimentResult result) throws IOException {
		List<ResultIndex.ResultIndexEntry> entries = new ArrayList<>();

		// Load existing index if present, filtering out any existing entry for this ID
		if (Files.isRegularFile(indexFile)) {
			ResultIndex existing = objectMapper.readValue(indexFile.toFile(), ResultIndex.class);
			for (ResultIndex.ResultIndexEntry entry : existing.entries()) {
				if (!entry.experimentId().equals(result.experimentId())) {
					entries.add(entry);
				}
			}
		}

		// Add new (or replacement) entry
		entries.add(new ResultIndex.ResultIndexEntry(result.experimentId(), result.timestamp(),
				result.experimentId() + ".json"));

		ResultIndex index = new ResultIndex(result.experimentName(), entries);

		// Atomic write for index too
		Path tempFile = Files.createTempFile(indexFile.getParent(), ".index-", ".tmp");
		try {
			objectMapper.writeValue(tempFile.toFile(), index);
			Files.move(tempFile, indexFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException ex) {
			Files.deleteIfExists(tempFile);
			throw ex;
		}
	}

}
