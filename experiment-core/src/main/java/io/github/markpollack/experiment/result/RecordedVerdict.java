package io.github.markpollack.experiment.result;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.markpollack.judge.jury.Verdict;

/**
 * Agent Experiment-owned persisted jury verdict.
 *
 * <p>
 * The representation follows Judge 0.14's complete composite-attempt tree while keeping
 * result files independent from Agent Judge implementation classes.
 */
public record RecordedVerdict(RecordedJudgment aggregated, List<RecordedJudgment> individual,
		Map<String, RecordedJudgment> individualByName, Map<String, Double> weights,
		List<RecordedCompositeAttempt> compositeAttempts) {

	public RecordedVerdict {
		java.util.Objects.requireNonNull(aggregated, "aggregated must not be null");
		individual = List.copyOf(individual);
		individualByName = Collections.unmodifiableMap(new LinkedHashMap<>(individualByName));
		weights = Collections.unmodifiableMap(new LinkedHashMap<>(weights));
		compositeAttempts = List.copyOf(compositeAttempts);
	}

	public static RecordedVerdict from(Verdict verdict) {
		Map<String, RecordedJudgment> byName = new LinkedHashMap<>();
		verdict.individualByName().forEach((name, judgment) -> byName.put(name, RecordedJudgment.from(judgment)));
		return new RecordedVerdict(RecordedJudgment.from(verdict.aggregated()),
				verdict.individual().stream().map(RecordedJudgment::from).toList(), byName, verdict.weights(),
				verdict.compositeAttempts().stream().map(RecordedCompositeAttempt::from).toList());
	}

}
