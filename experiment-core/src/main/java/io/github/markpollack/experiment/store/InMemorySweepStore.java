package io.github.markpollack.experiment.store;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

/**
 * In-memory {@link SweepStore} implementation for testing. Backed by a simple map keyed
 * by {@code "{experimentName}/{sweepName}"}.
 */
public class InMemorySweepStore implements SweepStore {

	private final Map<String, Sweep> sweeps = new LinkedHashMap<>();

	private final SessionStore sessionStore;

	public InMemorySweepStore(SessionStore sessionStore) {
		this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore must not be null");
	}

	@Override
	public Sweep createSweep(String sweepName, String experimentName, List<String> expectedVariants,
			Map<String, String> metadata) {
		if (expectedVariants.isEmpty()) {
			throw new IllegalArgumentException("expectedVariants must not be empty");
		}

		List<SweepVariantResolution> resolutions = expectedVariants.stream()
			.map(SweepVariantResolution::unresolved)
			.toList();

		Sweep sweep = new Sweep(sweepName, experimentName, Instant.now(), null, SweepStatus.RUNNING, expectedVariants,
				resolutions, List.of(), metadata);

		sweeps.put(key(experimentName, sweepName), sweep);
		return sweep;
	}

	@Override
	public void addSession(String sweepName, String experimentName, String sessionName, @Nullable String gitCommit) {
		Sweep current = loadRequired(experimentName, sweepName);
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

		sweeps.put(key(experimentName, sweepName),
				new Sweep(current.sweepName(), current.experimentName(), current.createdAt(), current.completedAt(),
						updatedStatus, current.expectedVariants(), updatedResolutions, updatedHistory,
						current.metadata()));
	}

	@Override
	public void removeSession(String sweepName, String experimentName, String sessionName) {
		Sweep current = loadRequired(experimentName, sweepName);

		List<SweepVariantResolution> updatedResolutions = current.resolutions().stream().map(r -> {
			if (sessionName.equals(r.sessionName())) {
				return SweepVariantResolution.unresolved(r.variantName());
			}
			return r;
		}).toList();

		sweeps.put(key(experimentName, sweepName),
				new Sweep(current.sweepName(), current.experimentName(), current.createdAt(), current.completedAt(),
						current.status(), current.expectedVariants(), updatedResolutions, current.sessionHistory(),
						current.metadata()));
	}

	@Override
	public void finalizeSweep(String sweepName, String experimentName, SweepStatus status) {
		Sweep current = loadRequired(experimentName, sweepName);

		sweeps.put(key(experimentName, sweepName),
				new Sweep(current.sweepName(), current.experimentName(), current.createdAt(), Instant.now(), status,
						current.expectedVariants(), current.resolutions(), current.sessionHistory(),
						current.metadata()));
	}

	@Override
	public Optional<Sweep> loadSweep(String experimentName, String sweepName) {
		return Optional.ofNullable(sweeps.get(key(experimentName, sweepName)));
	}

	@Override
	public List<Sweep> listSweeps(String experimentName) {
		return sweeps.values()
			.stream()
			.filter(s -> s.experimentName().equals(experimentName))
			.sorted(Comparator.comparing(Sweep::createdAt))
			.toList();
	}

	/** Returns the number of stored sweeps. */
	public int size() {
		return sweeps.size();
	}

	/** Clears all stored sweeps. */
	public void clear() {
		sweeps.clear();
	}

	private Sweep loadRequired(String experimentName, String sweepName) {
		return loadSweep(experimentName, sweepName)
			.orElseThrow(() -> new ResultStoreException("Sweep not found: " + sweepName, null));
	}

	private static String key(String experimentName, String sweepName) {
		return experimentName + "/" + sweepName;
	}

}
