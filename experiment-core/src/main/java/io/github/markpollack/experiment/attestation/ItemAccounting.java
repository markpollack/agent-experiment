package io.github.markpollack.experiment.attestation;

import java.util.List;

import io.github.markpollack.experiment.result.InstrumentRecord;
import io.github.markpollack.experiment.result.ItemCounts;
import io.github.markpollack.experiment.result.ItemResult;
import com.fasterxml.jackson.databind.JsonNode;
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
	public static @Nullable Boolean passedFlag(@Nullable JsonNode interpretation) {
		return switch (outcomeOf(interpretation, false)) {
			case PASS -> Boolean.TRUE;
			case NON_PASS -> Boolean.FALSE;
			case EXCLUDED, UNATTESTABLE, NOT_JUDGED -> null;
		};
	}

	/** Current conclusion accounting version: INCONCLUSIVE remains unknown. */
	public static @Nullable Boolean passedFlagCurrent(@Nullable JsonNode interpretation) {
		SubjectOutcome outcome = outcomeOf(interpretation, false);
		return switch (outcome) {
			case PASS -> true;
			case NON_PASS -> false;
			default -> null;
		};
	}

	public static SubjectOutcome outcomeOf(ItemResult item) {
		return subjectOutcome(item);
	}

	public static SubjectOutcome subjectOutcome(ItemResult item) {
		return outcomeOf(item.interpretation(), item.verdict() == null);
	}

	private static SubjectOutcome outcomeOf(@Nullable JsonNode reading, boolean noVerdict) {
		if (noVerdict)
			return SubjectOutcome.NOT_JUDGED;
		if (reading == null)
			return SubjectOutcome.UNATTESTABLE;
		if (reading.has("accountingVersion")) {
			if (!"experiment-v6-conclusion:v1".equals(reading.path("accountingVersion").asText()))
				return SubjectOutcome.UNATTESTABLE;
			return switch (reading.path("conclusion").asText()) {
				case "PASS" -> SubjectOutcome.PASS;
				case "FAIL" -> SubjectOutcome.NON_PASS;
				case "NOT_APPLICABLE" -> SubjectOutcome.EXCLUDED;
				default -> SubjectOutcome.UNATTESTABLE;
			};
		}
		// These are original historical observations, never a new reading of old
		// Verdicts.
		if ("CONTRADICTED".equals(reading.path("readingSupport").asText()))
			return SubjectOutcome.UNATTESTABLE;
		return switch (reading.path("reading").asText()) {
			case "ACCEPTED" -> SubjectOutcome.PASS;
			case "REJECTED", "UNDECIDED" -> SubjectOutcome.NON_PASS;
			case "NOT_APPLICABLE", "NOT_ASSESSED" -> SubjectOutcome.EXCLUDED;
			default -> SubjectOutcome.UNATTESTABLE;
		};
	}

	public static InstrumentHealth instrumentHealth(ItemResult item) {
		if (item.executionDetail() instanceof io.github.markpollack.experiment.judge.JudgeExecutionDetail detail
				&& (detail.invocationState().equals("THREW") || detail.invocationState().equals("NULL_RETURN")))
			return InstrumentHealth.FAILED;
		if (item.metadata().containsKey(InstrumentRecord.ITEM_INSTRUMENT_FAILURE))
			return InstrumentHealth.FAILED;
		JsonNode reading = item.interpretation();
		if (reading == null || reading.has("accountingVersion"))
			return InstrumentHealth.UNKNOWN;
		if (reading.path("root").hasNonNull("failure") || "NOT_ASSESSED".equals(reading.path("reading").asText()))
			return InstrumentHealth.FAILED;
		for (JsonNode stage : reading.path("stages"))
			if (stage.hasNonNull("failure"))
				return InstrumentHealth.FAILED;
		return reading.hasNonNull("reading") && !"CONTRADICTED".equals(reading.path("readingSupport").asText())
				? InstrumentHealth.OK : InstrumentHealth.UNKNOWN;
	}

	public static boolean instrumentFailed(ItemResult item) {
		return instrumentHealth(item) == InstrumentHealth.FAILED;
	}

}
