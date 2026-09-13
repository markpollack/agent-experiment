package io.github.markpollack.experiment.attestation;

import java.util.List;

import io.github.markpollack.experiment.result.ExperimentResult;
import io.github.markpollack.experiment.result.RecordedJudgmentStatus;
import org.jspecify.annotations.Nullable;

/**
 * How far a stored run's outcomes can be traced to the judges that produced them.
 *
 * <p>
 * A reader, not a record: it is computed from what a run stored and is never persisted.
 * Every figure here is a count taken on demand, following the rule that aggregates are
 * computed at read time from stored parts. It deliberately offers no pass rate. Whether
 * an item the jury could not score belongs in the denominator is a choice about the
 * question being asked, and the counts are what let a caller make it either way.
 *
 * @param experimentId the run's id
 * @param experimentName the run's experiment name
 * @param items one attestation per stored item, in stored order
 */
public record RunAttestation(String experimentId, String experimentName, List<ItemAttestation> items) {

	public RunAttestation {
		java.util.Objects.requireNonNull(experimentId, "experimentId must not be null");
		items = List.copyOf(items);
	}

	/**
	 * Read a run's attestation from what it stored, checking each verdict against the
	 * instrument the run recorded. Reads only; changes nothing.
	 */
	public static RunAttestation of(ExperimentResult result) {
		return new RunAttestation(result.experimentId(), result.experimentName(),
				result.items().stream().map(item -> ItemAttestation.of(item, result.instrument())).toList());
	}

	/** Items with the given attestability. */
	public long count(Attestability attestability) {
		return this.items.stream().filter(item -> item.attestability() == attestability).count();
	}

	/**
	 * Items whose verdict reached the given outcome; {@code null} counts the items that
	 * were never judged.
	 */
	public long count(@Nullable RecordedJudgmentStatus outcome) {
		return this.items.stream().filter(item -> item.outcome() == outcome).count();
	}

}
