package io.github.markpollack.experiment.attestation;

import java.util.List;
import java.util.Map;

import io.github.markpollack.experiment.result.InstrumentRecord;
import io.github.markpollack.experiment.result.ItemCounts;
import io.github.markpollack.experiment.result.ItemResult;
import io.github.markpollack.experiment.result.RecordedCompositeAttempt;
import io.github.markpollack.experiment.result.RecordedDecision;
import io.github.markpollack.experiment.result.RecordedJudgment;
import io.github.markpollack.experiment.result.RecordedJudgmentStatus;
import io.github.markpollack.experiment.result.RecordedVerdict;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The counting rules, pinned. These are the numeric oracles for how an item counts; every
 * pass rate anyone computes rests on them.
 */
class ItemAccountingTest {

	@Test
	void passFailAndAbstainAreAllScored() {
		ItemCounts counts = ItemAccounting.count(List.of(item("a", verdict(RecordedJudgmentStatus.PASS)),
				item("b", verdict(RecordedJudgmentStatus.FAIL)), item("c", verdict(RecordedJudgmentStatus.ABSTAIN))));

		// An abstention is about a criterion that applied, so it counts against the
		// subject.
		assertThat(counts).isEqualTo(new ItemCounts(1, 2, 0, 0, 0, 0));
		assertThat(counts.passRate()).hasValue(1.0 / 3);
	}

	@Test
	void notApplicableLeavesTheDenominatorAndAnAllExcludedRunHasNoRate() {
		ItemCounts counts = ItemAccounting.count(List.of(item("a", verdict(RecordedJudgmentStatus.NOT_APPLICABLE)),
				item("b", verdict(RecordedJudgmentStatus.NOT_APPLICABLE))));

		assertThat(counts).isEqualTo(new ItemCounts(0, 0, 2, 0, 0, 0));
		// Nothing was scored, so there is no rate. Reporting 0.0 would say the subject
		// failed everything, which is the opposite of what happened.
		assertThat(counts.passRate()).isEmpty();
	}

	@Test
	void anErrorIsExcludedFromTheSubjectAndCountedAsAnInstrumentFailure() {
		ItemCounts counts = ItemAccounting.count(List.of(item("a", verdict(RecordedJudgmentStatus.ERROR))));

		assertThat(counts).isEqualTo(new ItemCounts(0, 0, 1, 1, 0, 0));
		assertThat(counts.passRate()).isEmpty();
	}

	@Test
	void aRejectionWhoseAggregationAlsoFailedCountsAgainstTheSubjectAndAsAnInstrumentFailure() {
		// The mixed case: a tier stopped on one judge's established violation, and that
		// tier's own reduction then failed, so the aggregate is ERROR on top of a real
		// rejection. Excluding every ERROR would silently lose the rejection.
		RecordedVerdict mixed = new RecordedVerdict(judgment(RecordedJudgmentStatus.ERROR), List.of(), Map.of(),
				Map.of(), List.of(), new RecordedDecision("tier", "guardrail", "individual_rejection"), List.of(),
				null);

		ItemCounts counts = ItemAccounting.count(List.of(item("a", mixed)));

		assertThat(ItemAccounting.outcomeOf(item("a", mixed))).isEqualTo(SubjectOutcome.NON_PASS);
		assertThat(counts).isEqualTo(new ItemCounts(0, 1, 0, 1, 0, 0));
		assertThat(counts.passRate()).hasValue(0.0);
	}

	@Test
	void theOutcomeComesFromTheNamedTierTheDecisionPointsAt() {
		RecordedVerdict tier = new RecordedVerdict(judgment(RecordedJudgmentStatus.PASS), List.of(), Map.of(), Map.of(),
				List.of(), new RecordedDecision("own", null, null), List.of(), null);
		RecordedVerdict root = new RecordedVerdict(judgment(RecordedJudgmentStatus.ERROR), List.of(), Map.of(),
				Map.of(), List.of(), new RecordedDecision("tier", "quality", "tier_outcome"),
				List.of(new RecordedCompositeAttempt("quality", "cascade_tier", "FINAL_TIER", "used", null, tier,
						null)),
				null);

		// The root aggregate is ERROR, but the decision names the tier that decided, and
		// that tier passed. Reading the root's shape would invent a failure.
		assertThat(ItemAccounting.outcomeOf(item("a", root))).isEqualTo(SubjectOutcome.PASS);
	}

	@Test
	void aStageThatFailedIsCountedEvenWhenALaterTierPassed() {
		RecordedVerdict quality = new RecordedVerdict(judgment(RecordedJudgmentStatus.PASS), List.of(), Map.of(),
				Map.of(), List.of(), new RecordedDecision("own", null, null), List.of(), null);
		RecordedVerdict guardrail = new RecordedVerdict(judgment(RecordedJudgmentStatus.ERROR), List.of(), Map.of(),
				Map.of(), List.of(), new RecordedDecision("own", null, null), List.of(), null);
		RecordedVerdict root = new RecordedVerdict(judgment(RecordedJudgmentStatus.PASS), List.of(), Map.of(), Map.of(),
				List.of(), new RecordedDecision("tier", "quality", "tier_outcome"),
				List.of(new RecordedCompositeAttempt("guardrail", "cascade_tier", "REJECT_ON_ANY_FAIL", "stage_failed",
						"execution_failed", guardrail, null),
						new RecordedCompositeAttempt("quality", "cascade_tier", "FINAL_TIER", "used", null, quality,
								null)),
				null);

		ItemCounts counts = ItemAccounting.count(List.of(item("a", root)));

		// The subject passed and the instrument still failed. One number cannot say both.
		assertThat(counts).isEqualTo(new ItemCounts(1, 0, 0, 1, 0, 0));
	}

	@Test
	void aJuryThatDidNotConveneIsAnInstrumentFailureWhateverItDecided() {
		ItemResult marked = item("a", verdict(RecordedJudgmentStatus.PASS)).toBuilder()
			.metadata(Map.of(InstrumentRecord.ITEM_INSTRUMENT_FAILURE, "rosterMismatch"))
			.build();

		assertThat(ItemAccounting.count(List.of(marked))).isEqualTo(new ItemCounts(1, 0, 0, 1, 0, 0));
	}

	@Test
	void anItemThatNeverReachedAJuryIsNotJudgedAndNotAnInstrumentFailure() {
		ItemResult unjudged = ItemResult.builder().itemId("a").itemSlug("a").success(false).build();

		assertThat(ItemAccounting.outcomeOf(unjudged)).isEqualTo(SubjectOutcome.NOT_JUDGED);
		assertThat(ItemAccounting.count(List.of(unjudged))).isEqualTo(new ItemCounts(0, 0, 0, 0, 1, 0));
	}

	@Test
	void aRunWithNoItemsHasNoRateRatherThanZero() {
		assertThat(ItemAccounting.count(List.of()).passRate()).isEmpty();
	}

	@Test
	void aVerdictWithNoRecordedDecisionIsUnattestableRatherThanReadFromItsAggregate() {
		RecordedVerdict noDecision = new RecordedVerdict(judgment(RecordedJudgmentStatus.ERROR), List.of(), Map.of(),
				Map.of(), List.of(), null, List.of(), null);

		// Without a decision the outcome is not recoverable. Classifying it from the
		// aggregate would turn an absence into a definite answer.
		assertThat(ItemAccounting.outcomeOf(item("a", noDecision))).isEqualTo(SubjectOutcome.UNATTESTABLE);
		assertThat(ItemAccounting.count(List.of(item("a", noDecision)))).isEqualTo(new ItemCounts(0, 0, 0, 1, 0, 1));
	}

	@Test
	void aDecisionNamingAStageTheFileDoesNotContainIsUnattestable() {
		RecordedVerdict brokenEdge = new RecordedVerdict(judgment(RecordedJudgmentStatus.PASS), List.of(), Map.of(),
				Map.of(), List.of(), new RecordedDecision("tier", "quality", "tier_outcome"), List.of(), null);

		assertThat(ItemAccounting.outcomeOf(item("a", brokenEdge))).isEqualTo(SubjectOutcome.UNATTESTABLE);
	}

	@Test
	void aStageWhoseDispositionWasNeverRecordedIsUnattestable() {
		RecordedVerdict tier = new RecordedVerdict(judgment(RecordedJudgmentStatus.PASS), List.of(), Map.of(), Map.of(),
				List.of(), new RecordedDecision("own", null, null), List.of(), null);
		RecordedVerdict root = new RecordedVerdict(judgment(RecordedJudgmentStatus.PASS), List.of(), Map.of(), Map.of(),
				List.of(), new RecordedDecision("tier", "quality", "tier_outcome"),
				List.of(new RecordedCompositeAttempt("quality", "cascade_tier", "FINAL_TIER", null, null, tier, null)),
				null);

		// Missing stage evidence must not certify that the instrument was fine.
		assertThat(ItemAccounting.outcomeOf(item("a", root))).isEqualTo(SubjectOutcome.UNATTESTABLE);
	}

	private static ItemResult item(String id, RecordedVerdict verdict) {
		return ItemResult.builder().itemId(id).itemSlug(id).success(true).verdict(verdict).build();
	}

	private static RecordedVerdict verdict(RecordedJudgmentStatus status) {
		return new RecordedVerdict(judgment(status), List.of(), Map.of(), Map.of(), List.of(),
				new RecordedDecision("own", null, null), List.of(), null);
	}

	private static RecordedJudgment judgment(RecordedJudgmentStatus status) {
		return new RecordedJudgment(status, null, null, null, "recorded", List.of(), Map.of());
	}

}
