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
	public static @Nullable Boolean passedFlag(@Nullable RecordedVerdict verdict) {
		return switch (outcomeOf(ItemResult.builder().itemId("x").itemSlug("x").verdict(verdict).build())) {
			case PASS -> Boolean.TRUE;
			case NON_PASS -> Boolean.FALSE;
			case EXCLUDED, UNATTESTABLE, NOT_JUDGED -> null;
		};
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
		RecordedVerdict verdict = item.verdict();
		if (verdict == null) {
			return InstrumentHealth.UNKNOWN; // no jury ran; nothing says it was fine
		}
		if (item.metadata().containsKey(InstrumentRecord.ITEM_INSTRUMENT_FAILURE)) {
			return InstrumentHealth.FAILED;
		}
		if (anyStageFailed(verdict)) {
			return InstrumentHealth.FAILED;
		}
		if (!attestable(verdict)) {
			return InstrumentHealth.UNKNOWN;
		}
		return selectedDetermination(verdict).aggregated().status() == RecordedJudgmentStatus.ERROR
				? InstrumentHealth.FAILED : InstrumentHealth.OK;
	}

	/** True only when the instrument is known to have failed. */
	public static boolean instrumentFailed(ItemResult item) {
		return instrumentHealth(item) == InstrumentHealth.FAILED;
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
		if (decision == null || decision.kind() == null || decision.kind().isBlank()) {
			return false;
		}
		for (RecordedCompositeAttempt attempt : verdict.compositeAttempts()) {
			if (attempt.disposition() == null || attempt.disposition().isBlank()) {
				return false; // a stage whose usability was never recorded
			}
			if (attempt.verdict() != null && !attestable(attempt.verdict())) {
				return false;
			}
		}
		String kind = decision.kind();
		if ("own".equalsIgnoreCase(kind) || "undecided".equalsIgnoreCase(kind)) {
			return true;
		}
		if (!"tier".equalsIgnoreCase(kind)) {
			// A kind this version cannot read is not a kind it may assume is harmless.
			return false;
		}
		// A tier decision must say which stage and on what basis, and that stage must be
		// in the file. This holds for BOTH bases: an individual rejection whose tier is
		// missing is as unreadable as a tier outcome whose tier is missing, and treating
		// it as a rejection would assert a finding no stored stage supports.
		if (decision.tier() == null || decision.tier().isBlank() || decision.basis() == null
				|| decision.basis().isBlank()) {
			return false;
		}
		if (!decision.tierOutcome() && !decision.individualRejection()) {
			return false; // a basis this version cannot read
		}
		return namedAttempt(verdict, decision.tier()) != null;
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
