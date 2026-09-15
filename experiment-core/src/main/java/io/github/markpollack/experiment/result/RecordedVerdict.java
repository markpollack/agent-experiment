package io.github.markpollack.experiment.result;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.markpollack.judge.jury.Verdict;
import org.jspecify.annotations.Nullable;

/**
 * Agent Experiment-owned persisted jury verdict.
 *
 * <p>
 * The representation follows Judge 0.14's complete composite-attempt tree while keeping
 * result files independent from Agent Judge implementation classes.
 *
 * <p>
 * {@code instrumentHash} names the jury that produced this verdict, as the
 * {@link InstrumentRecord#specHash()} of the run that recorded it. It lives on the
 * verdict rather than only on the run because a re-evaluation replaces verdicts after the
 * fact: an item a re-evaluation skipped keeps its original verdict, scored by a different
 * jury from the one its new run records. Null when no instrument was recorded.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecordedVerdict(RecordedJudgment aggregated, List<RecordedJudgment> individual,
		Map<String, RecordedJudgment> individualByName, Map<String, Double> weights,
		List<RecordedCompositeAttempt> compositeAttempts, @Nullable String instrumentHash) {

	public RecordedVerdict {
		java.util.Objects.requireNonNull(aggregated, "aggregated must not be null");
		individual = List.copyOf(individual);
		individualByName = Collections.unmodifiableMap(new LinkedHashMap<>(individualByName));
		weights = Collections.unmodifiableMap(new LinkedHashMap<>(weights));
		compositeAttempts = List.copyOf(compositeAttempts);
	}

	/** A verdict whose instrument was not recorded. */
	public RecordedVerdict(RecordedJudgment aggregated, List<RecordedJudgment> individual,
			Map<String, RecordedJudgment> individualByName, Map<String, Double> weights,
			List<RecordedCompositeAttempt> compositeAttempts) {
		this(aggregated, individual, individualByName, weights, compositeAttempts, null);
	}

	public static RecordedVerdict from(Verdict verdict) {
		return from(verdict, null);
	}

	/**
	 * Record a live verdict together with the hash of the instrument that produced it.
	 */
	public static RecordedVerdict from(Verdict verdict, @Nullable String instrumentHash) {
		Map<String, RecordedJudgment> byName = new LinkedHashMap<>();
		verdict.individualByName().forEach((name, judgment) -> byName.put(name, RecordedJudgment.from(judgment)));
		return new RecordedVerdict(RecordedJudgment.from(verdict.aggregated()),
				verdict.individual().stream().map(RecordedJudgment::from).toList(), byName, verdict.weights(),
				verdict.compositeAttempts().stream().map(RecordedCompositeAttempt::from).toList(), instrumentHash);
	}

}
