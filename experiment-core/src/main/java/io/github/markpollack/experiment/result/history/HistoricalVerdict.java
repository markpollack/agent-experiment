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
 * @param weights weight per seat, each as recorded
 * @param seats the seats, or null when none were recorded
 * @param decision what produced the aggregate, or null when none was recorded
 * @param compositeAttempts the stages entered, as stored
 * @param instrumentHash the jury that produced this verdict, when recorded
 */
public record HistoricalVerdict(HistoricalJudgment aggregated, List<HistoricalJudgment> individual,
		Map<String, HistoricalJudgment> individualByName, Map<String, Double> weights,
		@Nullable List<HistoricalSeat> seats, @Nullable RecordedDecision decision,
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
		else {
			for (int i = 0; i < this.seats.size(); i++) {
				missing.addAll(this.seats.get(i).unrecorded(path + ".seats[" + i + "]"));
			}
		}
		missing.addAll(decisionUnrecorded(path));
		for (int i = 0; i < this.individual.size(); i++) {
			missing.addAll(this.individual.get(i).unrecorded(path + ".individual[" + i + "]"));
		}
		// The named map is what callers read a judge's verdict from, so a fact missing
		// only there still blocks conversion. Checking just the list would advertise a
		// conversion that then throws.
		this.individualByName
			.forEach((name, judgment) -> missing.addAll(judgment.unrecorded(path + ".individualByName[" + name + "]")));
		for (HistoricalCompositeAttempt attempt : this.compositeAttempts) {
			missing.addAll(attempt.unrecorded(path + ".attempt[" + attempt.name() + "]"));
		}
		return missing;
	}

	/**
	 * A decision must say enough to be followed. Its presence is not enough: a decision
	 * that names a deciding tier without saying what decided it, or names none at all,
	 * leaves the outcome unreadable.
	 */
	private List<String> decisionUnrecorded(String path) {
		if (this.decision == null) {
			return List.of(path + ".decision");
		}
		List<String> missing = new ArrayList<>();
		if (this.decision.kind() == null || this.decision.kind().isBlank()) {
			missing.add(path + ".decision.kind");
		}
		boolean tierKind = "tier".equalsIgnoreCase(this.decision.kind()) || "TIER".equals(this.decision.kind());
		if (tierKind) {
			if (this.decision.tier() == null || this.decision.tier().isBlank()) {
				missing.add(path + ".decision.tier");
			}
			if (this.decision.basis() == null || this.decision.basis().isBlank()) {
				missing.add(path + ".decision.basis");
			}
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
		List<RecordedSeat> liveSeats = this.seats == null ? null
				: this.seats.stream().map(HistoricalSeat::toLive).toList();
		return new RecordedVerdict(this.aggregated.toLive(),
				this.individual.stream().map(HistoricalJudgment::toLive).toList(), byName, this.weights, liveSeats,
				this.decision, this.compositeAttempts.stream().map(HistoricalCompositeAttempt::toLive).toList(),
				this.instrumentHash);
	}

}
