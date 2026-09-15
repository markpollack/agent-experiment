package io.github.markpollack.experiment.attestation;

import java.util.List;

import io.github.markpollack.experiment.result.InstrumentRecord;
import io.github.markpollack.experiment.result.ItemCounts;
import io.github.markpollack.experiment.result.ItemResult;
import io.github.markpollack.experiment.result.RecordedCompositeAttempt;
import io.github.markpollack.experiment.result.RecordedDecision;
import io.github.markpollack.experiment.result.RecordedJudgmentStatus;
import io.github.markpollack.experiment.result.RecordedVerdict;
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
 * <b>The subject outcome is read from the recorded decision, never inferred from the
 * shape of an aggregate.</b> A cascade that stopped because one judge rejected the
 * subject can carry an ERROR aggregate if that tier's reduction also failed. Reading
 * ERROR as "could not score" would lose a real rejection; reading it as a failure would
 * invent one. The decision says which happened, so the decision is what is read.
 */
public final class ItemAccounting {

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
			if (instrumentFailed(item)) {
				instrumentFailures++;
			}
		}
		return new ItemCounts(passes, nonPasses, excluded, instrumentFailures, notJudged, unattestable);
	}

	/** What this item says about the subject. */
	public static SubjectOutcome outcomeOf(ItemResult item) {
		RecordedVerdict verdict = item.verdict();
		if (verdict == null) {
			return SubjectOutcome.NOT_JUDGED;
		}
		if (!attestable(verdict)) {
			// The facts needed to read an outcome were never recorded. Falling back to
			// the aggregate's status here would manufacture a definite answer out of an
			// absence, which is the defect this record exists to prevent.
			return SubjectOutcome.UNATTESTABLE;
		}
		RecordedDecision decision = verdict.decision();
		if (decision != null && decision.individualRejection()) {
			// One judge established a violation and the cascade stopped on it. That is a
			// rejection of the subject even when the tier's own reduction then failed.
			return SubjectOutcome.NON_PASS;
		}
		RecordedVerdict determination = selectedDetermination(verdict);
		if (determination.decision() != null && determination.decision().individualRejection()) {
			return SubjectOutcome.NON_PASS;
		}
		return switch (determination.aggregated().status()) {
			case PASS -> SubjectOutcome.PASS;
			// An abstention is about a criterion that applied and could not be decided,
			// so
			// it counts against the subject. A not-applicable criterion was never
			// assessed.
			case FAIL, ABSTAIN -> SubjectOutcome.NON_PASS;
			case NOT_APPLICABLE, ERROR -> SubjectOutcome.EXCLUDED;
		};
	}

	/**
	 * Whether this item's instrument failed, independently of the subject outcome.
	 *
	 * <p>
	 * True when the deciding aggregate errored, when any stage the jury entered failed —
	 * which stays true even if a later tier passed — or when the jury did not convene
	 * with the judges it lists.
	 */
	public static boolean instrumentFailed(ItemResult item) {
		RecordedVerdict verdict = item.verdict();
		if (verdict == null) {
			return false;
		}
		if (item.metadata().containsKey(InstrumentRecord.ITEM_INSTRUMENT_FAILURE)) {
			return true;
		}
		if (selectedDetermination(verdict).aggregated().status() == RecordedJudgmentStatus.ERROR) {
			return true;
		}
		return anyStageFailed(verdict);
	}

	/**
	 * Whether this verdict recorded the facts an outcome must be read from.
	 *
	 * <p>
	 * Required, per the result-format contract: a stopping decision, complete enough to
	 * follow; a disposition on every stage the jury entered; and, where the decision
	 * names a stage, that stage actually present. A verdict written before those existed
	 * is unattestable rather than wrong — its outcome is not recoverable, and guessing it
	 * from the aggregate is what this refuses to do.
	 */
	public static boolean attestable(RecordedVerdict verdict) {
		RecordedDecision decision = verdict.decision();
		if (decision == null || decision.kind().isBlank()) {
			return false;
		}
		if (decision.tier() != null && decision.basis() == null) {
			return false; // names the deciding stage but not what decided it
		}
		for (RecordedCompositeAttempt attempt : verdict.compositeAttempts()) {
			if (attempt.disposition() == null || attempt.disposition().isBlank()) {
				return false; // a stage whose usability was never recorded
			}
			if (attempt.verdict() != null && !attestable(attempt.verdict())) {
				return false;
			}
		}
		if (decision.tierOutcome()) {
			return decision.tier() != null && namedAttempt(verdict, decision.tier()) != null;
		}
		return true;
	}

	/**
	 * The verdict that actually determined the outcome.
	 *
	 * <p>
	 * Follows only named copy edges: while a verdict says a tier decided it on that
	 * tier's own outcome, the determination is inside that named tier. A decision of its
	 * own, no decision at all, or a stop on one judge's rejection ends the walk.
	 */
	public static RecordedVerdict selectedDetermination(RecordedVerdict verdict) {
		RecordedVerdict current = verdict;
		// A cascade of cascades terminates because each step descends one level.
		while (true) {
			RecordedDecision decision = current.decision();
			if (decision == null || !decision.tierOutcome() || decision.tier() == null) {
				return current;
			}
			RecordedVerdict next = namedAttempt(current, decision.tier());
			if (next == null) {
				return current; // the named edge is missing; do not guess which stage
								// meant it
			}
			current = next;
		}
	}

	private static @Nullable RecordedVerdict namedAttempt(RecordedVerdict verdict, String name) {
		for (RecordedCompositeAttempt attempt : verdict.compositeAttempts()) {
			if (name.equals(attempt.name())) {
				return attempt.verdict();
			}
		}
		return null;
	}

	private static boolean anyStageFailed(RecordedVerdict verdict) {
		for (RecordedCompositeAttempt attempt : verdict.compositeAttempts()) {
			if (attempt.stageFailed() || attempt.failureCode() != null) {
				return true;
			}
			if (attempt.verdict() != null && anyStageFailed(attempt.verdict())) {
				return true;
			}
		}
		return false;
	}

}
