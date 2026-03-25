package io.github.markpollack.experiment.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Filesystem-backed {@link SweepStore} that persists sweeps as JSON files.
 *
 * <p>
 * Layout:
 * </p>
 *
 * <pre>
 * {baseDir}/{experimentName}/
 * ├── sweeps/
 * │   └── {sweepName}/
 * │       └── sweep.json
 * └── sweeps-index.json
 * </pre>
 *
 * <p>
 * Writes are atomic (temp file + {@link Files#move}) to prevent partial files on crashes.
 * </p>
 */
public class FileSystemSweepStore implements SweepStore {

	private static final Logger logger = LoggerFactory.getLogger(FileSystemSweepStore.class);

	private static final String SWEEP_FILE = "sweep.json";

	private static final String SWEEPS_DIR = "sweeps";

	private static final String SWEEPS_INDEX_FILE = "sweeps-index.json";

	private final Path baseDir;

	private final SessionStore sessionStore;

	private final ObjectMapper objectMapper;

	public FileSystemSweepStore(Path baseDir, SessionStore sessionStore) {
		this(baseDir, sessionStore, ResultObjectMapper.create());
	}

	FileSystemSweepStore(Path baseDir, SessionStore sessionStore, ObjectMapper objectMapper) {
		this.baseDir = Objects.requireNonNull(baseDir, "baseDir must not be null");
		this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore must not be null");
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
	}

	@Override
	public Sweep createSweep(String sweepName, String experimentName, List<String> expectedVariants,
			Map<String, String> metadata) {
		if (expectedVariants.isEmpty()) {
			throw new IllegalArgumentException("expectedVariants must not be empty");
		}

		Path sweepDir = sweepDir(experimentName, sweepName);
		Path sweepFile = sweepDir.resolve(SWEEP_FILE);

		List<SweepVariantResolution> resolutions = expectedVariants.stream()
			.map(SweepVariantResolution::unresolved)
			.toList();

		Sweep sweep = new Sweep(sweepName, experimentName, Instant.now(), null, SweepStatus.RUNNING, expectedVariants,
				resolutions, List.of(), metadata);

		try {
			Files.createDirectories(sweepDir);
			atomicWrite(sweepFile, sweep);
			updateSweepsIndex(experimentName, sweep);
			logger.debug("Created sweep {} for experiment {}", sweepName, experimentName);
			return sweep;
		}
		catch (IOException ex) {
			throw new ResultStoreException("Failed to create sweep " + sweepName + " for experiment " + experimentName,
					ex);
		}
	}

	@Override
	public void addSession(String sweepName, String experimentName, String sessionName, @Nullable String gitCommit) {
		Sweep current = loadSweepRequired(experimentName, sweepName);
		RunSession session = sessionStore.loadSession(experimentName, sessionName)
			.orElseThrow(() -> new ResultStoreException("Session not found: " + sessionName, null));

		Set<String> sessionVariantNames = session.variants()
			.stream()
			.map(VariantEntry::variantName)
			.collect(Collectors.toSet());

		Instant now = Instant.now();
		List<SweepVariantResolution> updatedResolutions = current.resolutions().stream().map(r -> {
			if (sessionVariantNames.contains(r.variantName())) {
				return new SweepVariantResolution(r.variantName(), sessionName, now, gitCommit);
			}
			return r;
		}).toList();

		List<String> updatedHistory = new ArrayList<>(current.sessionHistory());
		updatedHistory.add(sessionName);

		boolean hasResolved = updatedResolutions.stream().anyMatch(SweepVariantResolution::isResolved);
		SweepStatus updatedStatus = current.status();
		if (hasResolved && updatedStatus == SweepStatus.RUNNING) {
			updatedStatus = SweepStatus.PARTIAL;
		}

		Sweep updated = new Sweep(current.sweepName(), current.experimentName(), current.createdAt(),
				current.completedAt(), updatedStatus, current.expectedVariants(), updatedResolutions, updatedHistory,
				current.metadata());

		persistSweep(experimentName, updated);
		logger.debug("Added session {} to sweep {}", sessionName, sweepName);
	}

	@Override
	public void removeSession(String sweepName, String experimentName, String sessionName) {
		Sweep current = loadSweepRequired(experimentName, sweepName);

		List<SweepVariantResolution> updatedResolutions = current.resolutions().stream().map(r -> {
			if (sessionName.equals(r.sessionName())) {
				return SweepVariantResolution.unresolved(r.variantName());
			}
			return r;
		}).toList();

		Sweep updated = new Sweep(current.sweepName(), current.experimentName(), current.createdAt(),
				current.completedAt(), current.status(), current.expectedVariants(), updatedResolutions,
				current.sessionHistory(), current.metadata());

		persistSweep(experimentName, updated);
		logger.debug("Removed session {} from sweep {}", sessionName, sweepName);
	}

	@Override
	public void finalizeSweep(String sweepName, String experimentName, SweepStatus status) {
		Sweep current = loadSweepRequired(experimentName, sweepName);

		Sweep finalized = new Sweep(current.sweepName(), current.experimentName(), current.createdAt(), Instant.now(),
				status, current.expectedVariants(), current.resolutions(), current.sessionHistory(),
				current.metadata());

		persistSweep(experimentName, finalized);
		logger.debug("Finalized sweep {} with status {}", sweepName, status);
	}

	@Override
	public Optional<Sweep> loadSweep(String experimentName, String sweepName) {
		Path sweepFile = sweepDir(experimentName, sweepName).resolve(SWEEP_FILE);

		if (!Files.isRegularFile(sweepFile)) {
			return Optional.empty();
		}

		try {
			return Optional.of(objectMapper.readValue(sweepFile.toFile(), Sweep.class));
		}
		catch (IOException ex) {
			throw new ResultStoreException("Failed to load sweep " + sweepName + " for experiment " + experimentName,
					ex);
		}
	}

	@Override
	public List<Sweep> listSweeps(String experimentName) {
		Path indexFile = baseDir.resolve(experimentName).resolve(SWEEPS_INDEX_FILE);

		if (!Files.isRegularFile(indexFile)) {
			return List.of();
		}

		try {
			SweepIndex index = objectMapper.readValue(indexFile.toFile(), SweepIndex.class);
			List<Sweep> sweeps = new ArrayList<>();
			for (SweepIndex.SweepIndexEntry entry : index.entries()) {
				Path sweepFile = baseDir.resolve(experimentName).resolve(entry.path()).resolve(SWEEP_FILE);
				if (Files.isRegularFile(sweepFile)) {
					sweeps.add(objectMapper.readValue(sweepFile.toFile(), Sweep.class));
				}
				else {
					logger.warn("Sweep index references missing sweep: {}", sweepFile);
				}
			}
			sweeps.sort(Comparator.comparing(Sweep::createdAt));
			return List.copyOf(sweeps);
		}
		catch (IOException ex) {
			throw new ResultStoreException("Failed to list sweeps for " + experimentName, ex);
		}
	}

	private Sweep loadSweepRequired(String experimentName, String sweepName) {
		return loadSweep(experimentName, sweepName)
			.orElseThrow(() -> new ResultStoreException("Sweep not found: " + sweepName, null));
	}

	private void persistSweep(String experimentName, Sweep sweep) {
		Path sweepFile = sweepDir(experimentName, sweep.sweepName()).resolve(SWEEP_FILE);
		try {
			atomicWrite(sweepFile, sweep);
			updateSweepsIndex(experimentName, sweep);
		}
		catch (IOException ex) {
			throw new ResultStoreException(
					"Failed to persist sweep " + sweep.sweepName() + " for experiment " + experimentName, ex);
		}
	}

	private Path sweepDir(String experimentName, String sweepName) {
		return baseDir.resolve(experimentName).resolve(SWEEPS_DIR).resolve(sweepName);
	}

	private void atomicWrite(Path target, Object value) throws IOException {
		Path parent = target.getParent();
		Files.createDirectories(parent);
		Path tempFile = Files.createTempFile(parent, ".sweep-", ".tmp");
		try {
			objectMapper.writeValue(tempFile.toFile(), value);
			Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException ex) {
			Files.deleteIfExists(tempFile);
			throw ex;
		}
	}

	private void updateSweepsIndex(String experimentName, Sweep sweep) throws IOException {
		Path indexFile = baseDir.resolve(experimentName).resolve(SWEEPS_INDEX_FILE);
		List<SweepIndex.SweepIndexEntry> entries = new ArrayList<>();

		if (Files.isRegularFile(indexFile)) {
			SweepIndex existing = objectMapper.readValue(indexFile.toFile(), SweepIndex.class);
			for (SweepIndex.SweepIndexEntry entry : existing.entries()) {
				if (!entry.sweepName().equals(sweep.sweepName())) {
					entries.add(entry);
				}
			}
		}

		int resolvedCount = (int) sweep.resolutions().stream().filter(SweepVariantResolution::isResolved).count();

		entries.add(new SweepIndex.SweepIndexEntry(sweep.sweepName(), sweep.createdAt(), sweep.status(), resolvedCount,
				sweep.expectedVariants().size(), SWEEPS_DIR + "/" + sweep.sweepName() + "/"));

		SweepIndex index = new SweepIndex(experimentName, entries);
		atomicWrite(indexFile, index);
	}

}
