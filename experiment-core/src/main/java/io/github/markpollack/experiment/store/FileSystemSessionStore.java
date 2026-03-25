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
import java.util.Optional;

import io.github.markpollack.experiment.result.ExperimentResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Filesystem-backed {@link SessionStore} that persists sessions as JSON files.
 *
 * <p>
 * Layout:
 * </p>
 *
 * <pre>
 * {baseDir}/{experimentName}/
 * ├── sessions/
 * │   └── {sessionName}/
 * │       ├── session.json
 * │       ├── {variantName}.json
 * │       └── ...
 * └── sessions-index.json
 * </pre>
 *
 * <p>
 * Writes are atomic (temp file + {@link Files#move}) to prevent partial files on crashes,
 * matching the pattern in {@link FileSystemResultStore}.
 * </p>
 */
public class FileSystemSessionStore implements SessionStore {

	private static final Logger logger = LoggerFactory.getLogger(FileSystemSessionStore.class);

	private static final String SESSION_FILE = "session.json";

	private static final String SESSIONS_DIR = "sessions";

	private static final String SESSIONS_INDEX_FILE = "sessions-index.json";

	private final Path baseDir;

	private final ObjectMapper objectMapper;

	public FileSystemSessionStore(Path baseDir) {
		this(baseDir, ResultObjectMapper.create());
	}

	FileSystemSessionStore(Path baseDir, ObjectMapper objectMapper) {
		this.baseDir = java.util.Objects.requireNonNull(baseDir, "baseDir must not be null");
		this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper must not be null");
	}

	@Override
	public RunSession createSession(String sessionName, String experimentName, Map<String, String> metadata) {
		Path sessionDir = sessionDir(experimentName, sessionName);
		Path sessionFile = sessionDir.resolve(SESSION_FILE);

		RunSession session = new RunSession(sessionName, experimentName, Instant.now(), null, RunSessionStatus.RUNNING,
				List.of(), metadata);

		try {
			Files.createDirectories(sessionDir);
			atomicWrite(sessionFile, session);
			updateSessionsIndex(experimentName, session);
			logger.debug("Created session {} for experiment {}", sessionName, experimentName);
			return session;
		}
		catch (IOException ex) {
			throw new ResultStoreException(
					"Failed to create session " + sessionName + " for experiment " + experimentName, ex);
		}
	}

	@Override
	public void saveVariantToSession(String sessionName, String experimentName, String variantName,
			ExperimentResult result) {
		Path sessionDir = sessionDir(experimentName, sessionName);
		Path variantFile = sessionDir.resolve(variantName + ".json");
		Path sessionFile = sessionDir.resolve(SESSION_FILE);

		try {
			// Write variant result
			atomicWrite(variantFile, result);

			// Update session manifest
			RunSession current = objectMapper.readValue(sessionFile.toFile(), RunSession.class);
			VariantEntry entry = new VariantEntry(variantName, result.experimentId(), variantName + ".json",
					result.passRate(), result.items().size(), result.totalCostUsd(), result.totalDurationMs());

			// Replace existing entry for same variant or add new
			List<VariantEntry> updatedVariants = new ArrayList<>();
			boolean replaced = false;
			for (VariantEntry existing : current.variants()) {
				if (existing.variantName().equals(variantName)) {
					updatedVariants.add(entry);
					replaced = true;
				}
				else {
					updatedVariants.add(existing);
				}
			}
			if (!replaced) {
				updatedVariants.add(entry);
			}

			RunSession updated = new RunSession(current.sessionName(), current.experimentName(), current.createdAt(),
					current.completedAt(), current.status(), updatedVariants, current.metadata());
			atomicWrite(sessionFile, updated);

			logger.debug("Saved variant {} to session {}", variantName, sessionName);
		}
		catch (IOException ex) {
			throw new ResultStoreException("Failed to save variant " + variantName + " to session " + sessionName, ex);
		}
	}

	@Override
	public void finalizeSession(String sessionName, String experimentName, RunSessionStatus status) {
		Path sessionFile = sessionDir(experimentName, sessionName).resolve(SESSION_FILE);

		try {
			RunSession current = objectMapper.readValue(sessionFile.toFile(), RunSession.class);
			RunSession finalized = new RunSession(current.sessionName(), current.experimentName(), current.createdAt(),
					Instant.now(), status, current.variants(), current.metadata());
			atomicWrite(sessionFile, finalized);
			updateSessionsIndex(experimentName, finalized);

			logger.debug("Finalized session {} with status {}", sessionName, status);
		}
		catch (IOException ex) {
			throw new ResultStoreException("Failed to finalize session " + sessionName, ex);
		}
	}

	@Override
	public Optional<RunSession> loadSession(String experimentName, String sessionName) {
		Path sessionFile = sessionDir(experimentName, sessionName).resolve(SESSION_FILE);

		if (!Files.isRegularFile(sessionFile)) {
			return Optional.empty();
		}

		try {
			return Optional.of(objectMapper.readValue(sessionFile.toFile(), RunSession.class));
		}
		catch (IOException ex) {
			throw new ResultStoreException(
					"Failed to load session " + sessionName + " for experiment " + experimentName, ex);
		}
	}

	@Override
	public List<RunSession> listSessions(String experimentName) {
		Path indexFile = baseDir.resolve(experimentName).resolve(SESSIONS_INDEX_FILE);

		if (!Files.isRegularFile(indexFile)) {
			return List.of();
		}

		try {
			SessionIndex index = objectMapper.readValue(indexFile.toFile(), SessionIndex.class);
			List<RunSession> sessions = new ArrayList<>();
			for (SessionIndex.SessionIndexEntry entry : index.entries()) {
				Path sessionFile = baseDir.resolve(experimentName).resolve(entry.path()).resolve(SESSION_FILE);
				if (Files.isRegularFile(sessionFile)) {
					sessions.add(objectMapper.readValue(sessionFile.toFile(), RunSession.class));
				}
				else {
					logger.warn("Session index references missing session: {}", sessionFile);
				}
			}
			sessions.sort(Comparator.comparing(RunSession::createdAt));
			return List.copyOf(sessions);
		}
		catch (IOException ex) {
			throw new ResultStoreException("Failed to list sessions for " + experimentName, ex);
		}
	}

	@Override
	public Optional<RunSession> mostRecentSession(String experimentName) {
		Path indexFile = baseDir.resolve(experimentName).resolve(SESSIONS_INDEX_FILE);

		if (!Files.isRegularFile(indexFile)) {
			return Optional.empty();
		}

		try {
			SessionIndex index = objectMapper.readValue(indexFile.toFile(), SessionIndex.class);
			return index.entries()
				.stream()
				.max(Comparator.comparing(SessionIndex.SessionIndexEntry::createdAt))
				.flatMap(entry -> {
					Path sessionFile = baseDir.resolve(experimentName).resolve(entry.path()).resolve(SESSION_FILE);
					if (!Files.isRegularFile(sessionFile)) {
						logger.warn("Session index references missing session: {}", sessionFile);
						return Optional.empty();
					}
					try {
						return Optional.of(objectMapper.readValue(sessionFile.toFile(), RunSession.class));
					}
					catch (IOException ex) {
						throw new ResultStoreException("Failed to load session " + entry.sessionName(), ex);
					}
				});
		}
		catch (IOException ex) {
			throw new ResultStoreException("Failed to load most recent session for " + experimentName, ex);
		}
	}

	private Path sessionDir(String experimentName, String sessionName) {
		return baseDir.resolve(experimentName).resolve(SESSIONS_DIR).resolve(sessionName);
	}

	private void atomicWrite(Path target, Object value) throws IOException {
		Path parent = target.getParent();
		Files.createDirectories(parent);
		Path tempFile = Files.createTempFile(parent, ".session-", ".tmp");
		try {
			objectMapper.writeValue(tempFile.toFile(), value);
			Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException ex) {
			Files.deleteIfExists(tempFile);
			throw ex;
		}
	}

	private void updateSessionsIndex(String experimentName, RunSession session) throws IOException {
		Path indexFile = baseDir.resolve(experimentName).resolve(SESSIONS_INDEX_FILE);
		List<SessionIndex.SessionIndexEntry> entries = new ArrayList<>();

		if (Files.isRegularFile(indexFile)) {
			SessionIndex existing = objectMapper.readValue(indexFile.toFile(), SessionIndex.class);
			for (SessionIndex.SessionIndexEntry entry : existing.entries()) {
				if (!entry.sessionName().equals(session.sessionName())) {
					entries.add(entry);
				}
			}
		}

		entries.add(new SessionIndex.SessionIndexEntry(session.sessionName(), session.createdAt(), session.status(),
				session.variants().size(), SESSIONS_DIR + "/" + session.sessionName() + "/"));

		SessionIndex index = new SessionIndex(experimentName, entries);
		atomicWrite(indexFile, index);
	}

}
