package io.github.markpollack.experiment.store;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.markpollack.experiment.result.ExperimentResult;

/**
 * In-memory {@link ResultStore} implementation for testing. Backed by a simple map of
 * experiment ID to result.
 */
public class InMemoryResultStore implements ResultStore {

	private final Map<String, ExperimentResult> results = new LinkedHashMap<>();

	@Override
	public void save(ExperimentResult result) {
		results.put(result.experimentId(), result);
	}

	@Override
	public Optional<ExperimentResult> load(String id) {
		return Optional.ofNullable(results.get(id));
	}

	@Override
	public List<ExperimentResult> listByName(String experimentName) {
		return results.values()
			.stream()
			.filter(r -> r.experimentName().equals(experimentName))
			.sorted(Comparator.comparing(ExperimentResult::timestamp))
			.toList();
	}

	@Override
	public Optional<ExperimentResult> mostRecent(String experimentName) {
		return results.values()
			.stream()
			.filter(r -> r.experimentName().equals(experimentName))
			.max(Comparator.comparing(ExperimentResult::timestamp));
	}

	/** Returns the number of stored results. */
	public int size() {
		return results.size();
	}

	/** Clears all stored results. */
	public void clear() {
		results.clear();
	}

}
