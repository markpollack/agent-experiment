package io.github.markpollack.experiment.attestation;

import java.util.List;
import java.util.Map;

import io.github.markpollack.experiment.result.InstrumentRecord;
import io.github.markpollack.experiment.result.ItemCounts;
import io.github.markpollack.experiment.result.ItemResult;
import io.github.markpollack.experiment.result.RecordedJudgment;
import io.github.markpollack.experiment.result.RecordedJudgmentStatus;
import io.github.markpollack.experiment.result.RecordedVerdict;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The counting rules, pinned. These are the numeric oracles for how an item counts; every
 * pass rate anyone computes rests on them.
 *
 * <p>
 * They are written in terms of a <em>reading</em>, because that is now the input: what
 * the verdict says is the library's answer, and what it means for a measurement is the
 * only part decided here. Nothing in this class constructs a decision, a stage
 * disposition or an aggregate status to be interpreted — when those rules were pinned
 * here, they were a copy of someone else's.
 */
class ItemAccountingTest {

	enum Reading {

		ACCEPTED, REJECTED, UNDECIDED, NOT_APPLICABLE, NOT_ASSESSED

	}

	enum ReadingSupport {

		SUPPORTED, UNDETERMINED, CONTRADICTED

	}

	@Test
	void acceptedRejectedAndUndecidedAreAllScored() {
		ItemCounts counts = ItemAccounting
			.count(List.of(item("a", Reading.ACCEPTED), item("b", Reading.REJECTED), item("c", Reading.UNDECIDED)));

		// An abstention is about a criterion that applied, so it counts against the
		// subject.
		assertThat(counts).isEqualTo(new ItemCounts(1, 2, 0, 0, 0, 0));
		assertThat(counts.passRate()).hasValue(1.0 / 3);
	}

	@Test
	void notApplicableLeavesTheDenominatorAndAnAllExcludedRunHasNoRate() {
		ItemCounts counts = ItemAccounting
			.count(List.of(item("a", Reading.NOT_APPLICABLE), item("b", Reading.NOT_APPLICABLE)));

		assertThat(counts).isEqualTo(new ItemCounts(0, 0, 2, 0, 0, 0));
		// Nothing was scored, so there is no rate. Reporting 0.0 would say the subject
		// failed everything, which is the opposite of what happened.
		assertThat(counts.passRate()).isEmpty();
	}

	@Test
	void anUnassessedSubjectIsExcludedAndCountedAsAnInstrumentFailure() {
		ItemCounts counts = ItemAccounting.count(List.of(item("a", Reading.NOT_ASSESSED)));

		// The jury could not assess the subject. That is the instrument's failure, and
		// counting it against the subject is the defect this whole record exists to
		// remove.
		assertThat(counts).isEqualTo(new ItemCounts(0, 0, 1, 1, 0, 0));
		assertThat(counts.passRate()).isEmpty();
	}

	@Test
	void aRejectionWhoseStageAlsoFailedCountsAgainstTheSubjectAndAsAnInstrumentFailure() {
		// The mixed case: the subject was rejected and a stage of the jury also failed to
		// run. One number cannot say both, so both are recorded.
		ItemResult mixed = item("a", Reading.REJECTED, ReadingSupport.SUPPORTED, stageThatFailedToRun());

		assertThat(ItemAccounting.outcomeOf(mixed)).isEqualTo(SubjectOutcome.NON_PASS);
		assertThat(ItemAccounting.count(List.of(mixed))).isEqualTo(new ItemCounts(0, 1, 0, 1, 0, 0));
	}

	@Test
	void aStageThatFailedIsCountedEvenWhenTheSubjectPassed() {
		ItemResult item = item("a", Reading.ACCEPTED, ReadingSupport.SUPPORTED, stageThatFailedToRun());

		// The subject passed and the instrument still failed. One number cannot say both.
		assertThat(ItemAccounting.count(List.of(item))).isEqualTo(new ItemCounts(1, 0, 0, 1, 0, 0));
	}

	@Test
	void aJuryThatDidNotConveneIsAnInstrumentFailureWhateverItDecided() {
		ItemResult marked = item("a", Reading.ACCEPTED).toBuilder()
			.metadata(Map.of(InstrumentRecord.ITEM_INSTRUMENT_FAILURE, "rosterMismatch"))
			.build();

		assertThat(ItemAccounting.count(List.of(marked))).isEqualTo(new ItemCounts(1, 0, 0, 1, 0, 0));
	}

	@Test
	void anItemThatNeverReachedAJuryIsNotJudgedAndNotAnInstrumentFailure() {
		ItemResult unjudged = ItemResult.builder().historical(true).itemId("a").itemSlug("a").success(false).build();

		assertThat(ItemAccounting.outcomeOf(unjudged)).isEqualTo(SubjectOutcome.NOT_JUDGED);
		assertThat(ItemAccounting.count(List.of(unjudged))).isEqualTo(new ItemCounts(0, 0, 0, 0, 1, 0));
	}

	@Test
	void aRunWithNoItemsHasNoRateRatherThanZero() {
		assertThat(ItemAccounting.count(List.of()).passRate()).isEmpty();
	}

	@Test
	void aStoredVerdictWithNoReadingBesideItIsUnattestableRatherThanReadHere() {
		// Written before the reading existed and not yet re-exported. Deriving an outcome
		// from the verdict's own shape is exactly what this project stopped doing.
		ItemResult notReExported = ItemResult.builder()
			.historical(true)
			.itemId("a")
			.itemSlug("a")
			.success(true)
			.verdict(recordedVerdict())
			.build();

		assertThat(ItemAccounting.outcomeOf(notReExported)).isEqualTo(SubjectOutcome.UNATTESTABLE);
		// Nor may it claim the instrument was fine, or that it failed. The record does
		// not
		// say, and "not known to have failed" is not a clean bill of health.
		assertThat(ItemAccounting.instrumentHealth(notReExported)).isEqualTo(InstrumentHealth.UNKNOWN);
		assertThat(ItemAccounting.count(List.of(notReExported))).isEqualTo(new ItemCounts(0, 0, 0, 0, 0, 1));
	}

	@Test
	void aReadingTheRecordsOwnFactsContradictIsNeverCounted() {
		ItemResult contradicted = item("a", Reading.ACCEPTED, ReadingSupport.CONTRADICTED);

		// The file disputes its own reading. Counting it would publish a number the
		// record
		// itself argues with.
		assertThat(ItemAccounting.outcomeOf(contradicted)).isEqualTo(SubjectOutcome.UNATTESTABLE);
		assertThat(ItemAccounting.instrumentHealth(contradicted)).isEqualTo(InstrumentHealth.UNKNOWN);
		assertThat(ItemAccounting.count(List.of(contradicted))).isEqualTo(new ItemCounts(0, 0, 0, 0, 0, 1));
	}

	@Test
	void aReadingTheRecordCouldNotConfirmIsStillCounted() {
		// Almost every stored verdict predates aggregation evidence. Refusing these would
		// discard the archive rather than measure it.
		assertThat(ItemAccounting.COUNT_UNVERIFIED).isTrue();
		ItemResult unverified = item("a", Reading.ACCEPTED, ReadingSupport.UNDETERMINED);

		assertThat(ItemAccounting.outcomeOf(unverified)).isEqualTo(SubjectOutcome.PASS);
		assertThat(ItemAccounting.count(List.of(unverified))).isEqualTo(new ItemCounts(1, 0, 0, 0, 0, 0));
	}

	@Test
	void aVerdictWithNoReadableStatusIsUnattestableRatherThanDefaulted() {
		ItemResult noReading = item("a", null, ReadingSupport.UNDETERMINED);

		assertThat(ItemAccounting.outcomeOf(noReading)).isEqualTo(SubjectOutcome.UNATTESTABLE);
		assertThat(ItemAccounting.instrumentHealth(noReading)).isEqualTo(InstrumentHealth.UNKNOWN);
	}

	private static ItemResult item(String id, @Nullable Reading reading) {
		return item(id, reading, ReadingSupport.SUPPORTED);
	}

	private static ItemResult item(String id, @Nullable Reading reading, ReadingSupport support,
			com.fasterxml.jackson.databind.JsonNode... stages) {
		var interpretation = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
		interpretation.put("schemaVersion", 2).put("sourceVersion", 1);
		if (reading != null)
			interpretation.put("reading", reading.name());
		interpretation.put("readingSupport", support.name());
		interpretation.set("root", stage(null));
		interpretation.set("stages", new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(List.of(stages)));
		return ItemResult.builder()
			.historical(true)
			.itemId(id)
			.itemSlug(id)
			.success(true)
			.verdict(recordedVerdict())
			.interpretation(interpretation)
			.build();
	}

	private static com.fasterxml.jackson.databind.JsonNode stageThatFailedToRun() {
		return stage("jury_execution_failed");
	}

	private static com.fasterxml.jackson.databind.JsonNode stage(@Nullable String failure) {
		var stage = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
		if (failure != null)
			stage.put("failure", failure);
		return stage;
	}

	/**
	 * The stored projection still exists and is still written; it is simply no longer
	 * what an outcome is read from.
	 */
	private static RecordedVerdict recordedVerdict() {
		return new RecordedVerdict(
				new RecordedJudgment(RecordedJudgmentStatus.PASS, null, null, null, "recorded", List.of(), Map.of()),
				List.of(), Map.of(), Map.of(), List.of(), null, List.of(), null);
	}

}
