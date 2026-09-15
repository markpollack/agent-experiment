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
 * The representation follows Agent Judge's complete composite-attempt tree while keeping
 * result files independent from Agent Judge implementation classes.
 *
 * <p>
 * {@code instrumentHash} names the jury that produced this verdict, as the
 * {@link InstrumentRecord#specHash()} of the run that recorded it. It lives on the
 * verdict rather than only on the run because a re-evaluation replaces verdicts after the
 * fact: an item a re-evaluation skipped keeps its original verdict, scored by a different
 * jury from the one its new run records. Null when no instrument was recorded.
 *
 * @param aggregated the jury's aggregate judgment
 * @param individual each judgment in seat order
 * @param individualByName each judgment under its verdict key
 * @param weights weight per seat position
 * @param seats where each judgment sat and under what key; null when the verdict recorded
 * no seats at all, which is not the same as recording that there were none
 * @param decision what produced the aggregate, and which tier decided it
 * @param compositeAttempts every stage entered, with its disposition
 * @param instrumentHash the jury that produced this verdict
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecordedVerdict(RecordedJudgment aggregated, List<RecordedJudgment> individual,
		Map<String, RecordedJudgment> individualByName, Map<String, Double> weights, @Nullable List<RecordedSeat> seats,
		@Nullable RecordedDecision decision, List<RecordedCompositeAttempt> compositeAttempts,
		@Nullable String instrumentHash) {

	public RecordedVerdict {
		java.util.Objects.requireNonNull(aggregated, "aggregated must not be null");
		individual = List.copyOf(individual);
		individualByName = Collections.unmodifiableMap(new LinkedHashMap<>(individualByName));
		weights = Collections.unmodifiableMap(new LinkedHashMap<>(weights));
		// An absent seat list stays absent. Coercing it to empty would say "nobody voted"
		// where the file says only "this was never recorded".
		seats = seats == null ? null : List.copyOf(seats);
		compositeAttempts = List.copyOf(compositeAttempts);
	}

	/** A verdict recorded before seats, decisions and instrument hashes existed. */
	public RecordedVerdict(RecordedJudgment aggregated, List<RecordedJudgment> individual,
			Map<String, RecordedJudgment> individualByName, Map<String, Double> weights,
			List<RecordedCompositeAttempt> compositeAttempts) {
		this(aggregated, individual, individualByName, weights, null, null, compositeAttempts, null);
	}

	/** A verdict recorded before seats and decisions existed. */
	public RecordedVerdict(RecordedJudgment aggregated, List<RecordedJudgment> individual,
			Map<String, RecordedJudgment> individualByName, Map<String, Double> weights,
			List<RecordedCompositeAttempt> compositeAttempts, @Nullable String instrumentHash) {
		this(aggregated, individual, individualByName, weights, null, null, compositeAttempts, instrumentHash);
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
				verdict.seats().stream().map(RecordedSeat::from).toList(), RecordedDecision.from(verdict.decision()),
				verdict.compositeAttempts().stream().map(RecordedCompositeAttempt::from).toList(), instrumentHash);
	}

}
