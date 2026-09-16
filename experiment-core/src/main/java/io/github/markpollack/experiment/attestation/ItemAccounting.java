package io.github.markpollack.experiment.attestation;

import java.util.List;

import io.github.markpollack.experiment.result.InstrumentRecord;
import io.github.markpollack.experiment.result.ItemCounts;
import io.github.markpollack.experiment.result.ItemResult;
import io.github.markpollack.judge.jury.interpretation.Interpretation;
import io.github.markpollack.judge.jury.interpretation.ReadingSupport;
import io.github.markpollack.judge.jury.interpretation.Stage;
import io.github.markpollack.judge.jury.interpretation.VerdictReading;
import org.jspecify.annotations.Nullable;

/**
 * How an item counts.
 *
 * <p>
 * One place decides it, because a rule applied in three places becomes three rules. The
 * writers use it to record what a run observed and readers use it to recompute; both get
 * the same answer by construction.
 *
 * <p>
 * <b>What a verdict says is not decided here.</b> The library that produced the verdict
 * reads it — which stage decided, what it says about the subject, what the record is
 * missing — and this maps that reading onto a measurement. The mapping is the only part
 * that is this project's to own, and it is the whole of what follows: an abstention
 * counts against the subject, a not-applicable criterion leaves the denominator, an
 * instrument failure is counted apart from the subject, and nothing is ever defaulted to
 * a pass or a failure.
 */
public final class ItemAccounting {

	/**
	 * A reading the record's own facts could not confirm is still counted.
	 *
	 * <p>
	 * Almost every stored verdict predates aggregation evidence, so refusing to count an
	 * unconfirmed reading would discard the archive rather than measure it. What is
	 * refused is a reading the facts <em>contradict</em>: that is a record whose parts
	 * disagree, and counting it would publish a number the file itself disputes.
	 */
	public static final boolean COUNT_UNVERIFIED = true;

	private ItemAccounting() {
	}

	/** Count a run's items into the parts a pass rate is made of. */
	public static ItemCounts count(List<ItemResult> items) {
		int passes = 0, nonPasses = 0, excluded = 0, instrumentFailures = 0, notJudged = 0, unattestable = 0;
		for (ItemResult item : items) {
			switch (outcomeOf(item)) {
				case PASS -> passes++;
				case NON_PASS -> nonPasses++;
				case EXCLUDED -> excluded++;
				case NOT_JUDGED -> notJudged++;
				case UNATTESTABLE -> unattestable++;
			}
			if (instrumentHealth(item) == InstrumentHealth.FAILED) {
				instrumentFailures++;
			}
		}
		return new ItemCounts(passes, nonPasses, excluded, instrumentFailures, notJudged, unattestable);
	}

	/**
	 * Whether the jury passed the subject, or null when it decided nothing about it.
	 *
	 * <p>
	 * Null rather than false for an excluded, unattestable or unjudged item. A run nobody
	 * judged and a run that failed everything are different results; recording false for
	 * both is how they came to render identically.
	 */
	public static @Nullable Boolean passedFlag(@Nullable Interpretation interpretation) {
		return switch (outcomeOf(interpretation, false)) {
			case PASS -> Boolean.TRUE;
			case NON_PASS -> Boolean.FALSE;
			case EXCLUDED, UNATTESTABLE, NOT_JUDGED -> null;
		};
	}

	/** What this item says about the subject. */
	public static SubjectOutcome outcomeOf(ItemResult item) {
		return outcomeOf(item.interpretation(), item.verdict() == null);
	}

	private static SubjectOutcome outcomeOf(@Nullable Interpretation interpretation, boolean noVerdict) {
		if (noVerdict) {
			return SubjectOutcome.NOT_JUDGED;
		}
		if (interpretation == null) {
			// A verdict with no reading beside it. Stored before the reading existed and
			// not yet re-exported: its outcome is not recoverable here, and deriving one
			// from the verdict's shape is the thing this record exists to stop.
			return SubjectOutcome.UNATTESTABLE;
		}
		if (interpretation.readingSupport() == ReadingSupport.CONTRADICTED) {
			return SubjectOutcome.UNATTESTABLE;
		}
		VerdictReading reading = interpretation.reading();
		if (reading == null) {
			return SubjectOutcome.UNATTESTABLE;
		}
		return switch (reading) {
			case ACCEPTED -> SubjectOutcome.PASS;
			// An abstention is about a criterion that applied and could not be decided,
			// so
			// it counts against the subject. A not-applicable criterion was never
			// assessed,
			// and an unassessed subject is the instrument's failure, not the subject's.
			case REJECTED, UNDECIDED -> SubjectOutcome.NON_PASS;
			case NOT_APPLICABLE, NOT_ASSESSED -> SubjectOutcome.EXCLUDED;
		};
	}

	/**
	 * What this item says about the instrument that scored it, independently of the
	 * subject outcome.
	 *
	 * <p>
	 * Three states, not two. A record that never said whether a stage was usable cannot
	 * report that the instrument was fine, so it reports {@link InstrumentHealth#UNKNOWN}
	 * rather than a comfortable false. Two states here would rebuild, one level along,
	 * the defect this whole record exists to remove.
	 */
	public static InstrumentHealth instrumentHealth(ItemResult item) {
		if (item.verdict() == null) {
			return InstrumentHealth.UNKNOWN; // no jury ran; nothing says it was fine
		}
		if (item.metadata().containsKey(InstrumentRecord.ITEM_INSTRUMENT_FAILURE)) {
			return InstrumentHealth.FAILED;
		}
		Interpretation interpretation = item.interpretation();
		if (interpretation == null) {
			return InstrumentHealth.UNKNOWN;
		}
		if (anyStageFailedToRun(interpretation)) {
			return InstrumentHealth.FAILED;
		}
		if (interpretation.reading() == VerdictReading.NOT_ASSESSED) {
			return InstrumentHealth.FAILED;
		}
		if (interpretation.reading() == null || interpretation.readingSupport() == ReadingSupport.CONTRADICTED) {
			return InstrumentHealth.UNKNOWN;
		}
		return InstrumentHealth.OK;
	}

	/** True only when the instrument is known to have failed. */
	public static boolean instrumentFailed(ItemResult item) {
		return instrumentHealth(item) == InstrumentHealth.FAILED;
	}

	/**
	 * A stage that entered and never produced a verdict: the jury could not be run there.
	 * The library reports it; this only has to decide that it counts against the
	 * instrument rather than against the subject.
	 */
	private static boolean anyStageFailedToRun(Interpretation interpretation) {
		if (interpretation.root().failure() != null) {
			return true;
		}
		for (Stage stage : interpretation.stages()) {
			if (stage.failure() != null) {
				return true;
			}
		}
		return false;
	}

}
