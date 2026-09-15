package io.github.markpollack.experiment.result.history;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.markpollack.experiment.result.RecordedDecision;
import io.github.markpollack.experiment.result.RecordedJudgment;
import io.github.markpollack.experiment.result.RecordedSeat;
import io.github.markpollack.experiment.result.RecordedVerdict;
import org.jspecify.annotations.Nullable;

/**
 * A verdict as it was stored, including the facts it did not store.
 *
 * <p>
 * <b>An absent seat list is not an empty one.</b> {@code seats} is null when the file
 * recorded no seats at all — a verdict written before seats existed — and an empty list
 * when it recorded that there were none. Collapsing the two would turn "we do not know
 * who voted" into "nobody voted", which is the class of defect this whole format exists
 * to remove.
 *
 * @param aggregated the aggregate judgment
 * @param individual each judgment, as stored
 * @param individualByName each judgment under its verdict key
 * @param weights weight per seat
 * @param seats the seats, or null when none were recorded
 * @param decision what produced the aggregate, or null when none was recorded
 * @param compositeAttempts the stages entered, as stored
 * @param instrumentHash the jury that produced this verdict, when recorded
 */
public record HistoricalVerdict(HistoricalJudgment aggregated, List<HistoricalJudgment> individual,
		Map<String, HistoricalJudgment> individualByName, Map<String, Double> weights,
		@Nullable List<RecordedSeat> seats, @Nullable RecordedDecision decision,
		List<HistoricalCompositeAttempt> compositeAttempts, @Nullable String instrumentHash) {

	public HistoricalVerdict {
		java.util.Objects.requireNonNull(aggregated, "aggregated must not be null");
		individual = List.copyOf(individual);
		individualByName = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(individualByName));
		weights = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(weights));
		seats = seats == null ? null : List.copyOf(seats);
		compositeAttempts = List.copyOf(compositeAttempts);
	}

	/** Required facts this verdict did not record, as paths. Empty means convertible. */
	public List<String> unrecorded(String path) {
		List<String> missing = new ArrayList<>(this.aggregated.unrecorded(path + ".aggregated"));
		if (this.seats == null) {
			missing.add(path + ".seats");
		}
		if (this.decision == null) {
			missing.add(path + ".decision");
		}
		for (int i = 0; i < this.individual.size(); i++) {
			missing.addAll(this.individual.get(i).unrecorded(path + ".individual[" + i + "]"));
		}
		for (HistoricalCompositeAttempt attempt : this.compositeAttempts) {
			missing.addAll(attempt.unrecorded(path + ".attempt[" + attempt.name() + "]"));
		}
		return missing;
	}

	/**
	 * True when every required fact was recorded, so this verdict can be read as live.
	 */
	public boolean attestable() {
		return unrecorded("verdict").isEmpty();
	}

	/**
	 * The live verdict this stored one represents.
	 * @throws IllegalStateException when a required fact was never recorded; the message
	 * names every missing path, and nothing is invented
	 */
	public RecordedVerdict toLive() {
		List<String> missing = unrecorded("verdict");
		if (!missing.isEmpty()) {
			throw new IllegalStateException("cannot convert a verdict with unrecorded required facts: " + missing);
		}
		Map<String, RecordedJudgment> byName = new LinkedHashMap<>();
		this.individualByName.forEach((name, judgment) -> byName.put(name, judgment.toLive()));
		return new RecordedVerdict(this.aggregated.toLive(),
				this.individual.stream().map(HistoricalJudgment::toLive).toList(), byName, this.weights, this.seats,
				this.decision, this.compositeAttempts.stream().map(HistoricalCompositeAttempt::toLive).toList(),
				this.instrumentHash);
	}

}
