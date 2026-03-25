package io.github.markpollack.experiment.store;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.markpollack.experiment.result.ExperimentResult;

/**
 * In-memory {@link SessionStore} implementation for testing. Backed by a simple map keyed
 * by {@code "{experimentName}/{sessionName}"}.
 */
public class InMemorySessionStore implements SessionStore {

	private final Map<String, RunSession> sessions = new LinkedHashMap<>();

	@Override
	public RunSession createSession(String sessionName, String experimentName, Map<String, String> metadata) {
		RunSession session = new RunSession(sessionName, experimentName, Instant.now(), null, RunSessionStatus.RUNNING,
				List.of(), metadata);
		sessions.put(key(experimentName, sessionName), session);
		return session;
	}

	@Override
	public void saveVariantToSession(String sessionName, String experimentName, String variantName,
			ExperimentResult result) {
		String key = key(experimentName, sessionName);
		RunSession current = sessions.get(key);
		if (current == null) {
			throw new ResultStoreException("Session not found: " + sessionName, null);
		}

		VariantEntry entry = new VariantEntry(variantName, result.experimentId(), variantName + ".json",
				result.passRate(), result.items().size(), result.totalCostUsd(), result.totalDurationMs());

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

		sessions.put(key, new RunSession(current.sessionName(), current.experimentName(), current.createdAt(),
				current.completedAt(), current.status(), updatedVariants, current.metadata()));
	}

	@Override
	public void finalizeSession(String sessionName, String experimentName, RunSessionStatus status) {
		String key = key(experimentName, sessionName);
		RunSession current = sessions.get(key);
		if (current == null) {
			throw new ResultStoreException("Session not found: " + sessionName, null);
		}

		sessions.put(key, new RunSession(current.sessionName(), current.experimentName(), current.createdAt(),
				Instant.now(), status, current.variants(), current.metadata()));
	}

	@Override
	public Optional<RunSession> loadSession(String experimentName, String sessionName) {
		return Optional.ofNullable(sessions.get(key(experimentName, sessionName)));
	}

	@Override
	public List<RunSession> listSessions(String experimentName) {
		return sessions.values()
			.stream()
			.filter(s -> s.experimentName().equals(experimentName))
			.sorted(Comparator.comparing(RunSession::createdAt))
			.toList();
	}

	@Override
	public Optional<RunSession> mostRecentSession(String experimentName) {
		return sessions.values()
			.stream()
			.filter(s -> s.experimentName().equals(experimentName))
			.max(Comparator.comparing(RunSession::createdAt));
	}

	/** Returns the number of stored sessions. */
	public int size() {
		return sessions.size();
	}

	/** Clears all stored sessions. */
	public void clear() {
		sessions.clear();
	}

	private static String key(String experimentName, String sessionName) {
		return experimentName + "/" + sessionName;
	}

}
