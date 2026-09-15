package io.github.markpollack.experiment.reeval;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.markpollack.experiment.agent.InvocationResult;
import io.github.markpollack.experiment.result.ExperimentResult;
import io.github.markpollack.experiment.result.ItemResult;
import io.github.markpollack.experiment.result.RecordedVerdict;
import io.github.markpollack.experiment.store.InMemoryResultStore;
import io.github.markpollack.judge.Judge;
import io.github.markpollack.judge.context.JudgmentContext;
import io.github.markpollack.judge.jury.Jury;
import io.github.markpollack.judge.jury.MajorityVotingStrategy;
import io.github.markpollack.judge.jury.SimpleJury;
import io.github.markpollack.judge.jury.Verdict;
import io.github.markpollack.judge.result.Judgment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReEvaluatorTest {

	private InMemoryResultStore resultStore;

	@BeforeEach
	void setUp() {
		resultStore = new InMemoryResultStore();
	}

	@Test
	void reEvaluateChangesScores() {
		ExperimentResult original = createOriginalResult(true);
		resultStore.save(original);

		// Re-evaluate with a failing jury
		ReEvaluator reEvaluator = ReEvaluator.agentDefaults(resultStore);
		ExperimentResult reEvaluated = reEvaluator.reEvaluate(original, juryWith(failingJudge()));

		assertThat(reEvaluated.experimentId()).isNotEqualTo(original.experimentId());
		assertThat(reEvaluated.experimentName()).isEqualTo(original.experimentName());
		assertThat(reEvaluated.counts().passes()).isZero();
		assertThat(reEvaluated.counts().nonPasses()).isEqualTo(1);
		assertThat(reEvaluated.counts().passRate()).hasValue(0.0);
		assertThat(reEvaluated.items().get(0).passed()).isFalse();
	}

	@Test
	void skippedItemsPreservedCorrectly() {
		// Create a result with a failed item (no execution detail)
		ItemResult failedItem = ItemResult.builder()
			.itemId("ITEM-FAILED")
			.itemSlug("failed-item")
			.success(false)
			.passed(false)
			.build();

		ExperimentResult original = experimentWith(List.of(failedItem));
		resultStore.save(original);

		ReEvaluator reEvaluator = ReEvaluator.agentDefaults(resultStore);
		ExperimentResult reEvaluated = reEvaluator.reEvaluate(original, juryWith(passingJudge()));

		ItemResult skipped = reEvaluated.items().get(0);
		assertThat(skipped.metadata()).containsEntry("reEvaluationSkipped", "true");
		assertThat(skipped.metadata()).containsEntry("reEvaluated", "false");
		assertThat(skipped.metadata()).containsKey("reEvaluationSkipReason");
	}

	@Test
	void metadataRecordsProvenance() {
		ExperimentResult original = createOriginalResult(true);
		resultStore.save(original);

		ReEvaluator reEvaluator = ReEvaluator.agentDefaults(resultStore);
		ExperimentResult reEvaluated = reEvaluator.reEvaluate(original, juryWith(passingJudge()));

		assertThat(reEvaluated.metadata()).containsEntry("reEvaluatedFrom", original.experimentId());
		assertThat(reEvaluated.metadata()).containsEntry("systemReinvoked", "false");
		assertThat(reEvaluated.metadata()).containsKey("originalTimestamp");

		// Item-level metadata
		ItemResult item = reEvaluated.items().get(0);
		assertThat(item.metadata()).containsEntry("reEvaluated", "true");
		assertThat(item.metadata()).containsEntry("systemReinvoked", "false");
	}

	@Test
	void loadByIdConvenience() {
		ExperimentResult original = createOriginalResult(true);
		resultStore.save(original);

		ReEvaluator reEvaluator = ReEvaluator.agentDefaults(resultStore);
		ExperimentResult reEvaluated = reEvaluator.reEvaluate(original.experimentId(), juryWith(passingJudge()));

		assertThat(reEvaluated.metadata()).containsEntry("reEvaluatedFrom", original.experimentId());
	}

	@Test
	void loadByIdThrowsForMissing() {
		ReEvaluator reEvaluator = ReEvaluator.agentDefaults(resultStore);

		assertThatThrownBy(() -> reEvaluator.reEvaluate("nonexistent", juryWith(passingJudge())))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("nonexistent");
	}

	@Test
	void customContextFactoryViaLambda() {
		ExperimentResult original = createOriginalResult(true);
		resultStore.save(original);

		// Custom factory that uses item metadata for context
		ReEvaluationContextFactory customFactory = item -> {
			if (!item.success()) {
				return Optional.empty();
			}
			return Optional.of(JudgmentContext.builder().goal("custom-goal-" + item.itemId()).build());
		};

		ReEvaluator reEvaluator = ReEvaluator.builder().resultStore(resultStore).contextFactory(customFactory).build();

		ExperimentResult reEvaluated = reEvaluator.reEvaluate(original, juryWith(passingJudge()));

		assertThat(reEvaluated.items().get(0).passed()).isTrue();
	}

	@Test
	void reEvaluatedResultPersistedViaResultStore() {
		ExperimentResult original = createOriginalResult(true);
		resultStore.save(original);

		ReEvaluator reEvaluator = ReEvaluator.agentDefaults(resultStore);
		ExperimentResult reEvaluated = reEvaluator.reEvaluate(original, juryWith(passingJudge()));

		// Both original and re-evaluated should be in the store
		assertThat(resultStore.size()).isEqualTo(2);
		assertThat(resultStore.load(reEvaluated.experimentId())).isPresent();
	}

	@Test
	void preservesOriginalCost() {
		ExperimentResult original = createOriginalResult(true);
		resultStore.save(original);

		ReEvaluator reEvaluator = ReEvaluator.agentDefaults(resultStore);
		ExperimentResult reEvaluated = reEvaluator.reEvaluate(original, juryWith(passingJudge()));

		assertThat(reEvaluated.totalCostUsd()).isEqualTo(original.totalCostUsd());
	}

	@Test
	void reEvaluationToComparisonPipeline() {
		ExperimentResult original = createOriginalResult(false);
		resultStore.save(original);

		// Re-evaluate with a passing jury
		ReEvaluator reEvaluator = ReEvaluator.agentDefaults(resultStore);
		ExperimentResult reEvaluated = reEvaluator.reEvaluate(original, juryWith(passingJudge()));

		// Compare original vs re-evaluated via ComparisonEngine
		var engine = new io.github.markpollack.experiment.comparison.DefaultComparisonEngine(resultStore);
		var comparison = engine.compare(reEvaluated, original);

		assertThat(comparison).isNotNull();
		assertThat(comparison.currentExperimentId()).isEqualTo(reEvaluated.experimentId());
		assertThat(comparison.baselineExperimentId()).isEqualTo(original.experimentId());
	}

	@Test
	void reEvaluationRecordsItsOwnJuryOnTheRunAndOnEachRescoredVerdict() {
		ExperimentResult original = createOriginalResult(true);
		resultStore.save(original);

		ReEvaluator reEvaluator = ReEvaluator.agentDefaults(resultStore);
		ExperimentResult reEvaluated = reEvaluator.reEvaluate(original, juryWith(failingJudge()));

		assertThat(reEvaluated.instrument()).isNotNull();
		assertThat(reEvaluated.instrument().specHash()).isNotNull();
		assertThat(reEvaluated.items().get(0).verdict().instrumentHash())
			.isEqualTo(reEvaluated.instrument().specHash());
	}

	@Test
	void skippedItemKeepsTheInstrumentItWasScoredByAndIsNotCheckedAgainstTheNewOne() {
		Verdict originalVerdict = juryWith(passingJudge()).vote(JudgmentContext.builder().goal("original").build());
		ItemResult failedButJudged = ItemResult.builder()
			.itemId("ITEM-FAILED")
			.itemSlug("failed-item")
			.success(false)
			.verdict(RecordedVerdict.from(originalVerdict, "original-instrument"))
			.build();
		ExperimentResult original = experimentWith(List.of(failedButJudged));
		resultStore.save(original);

		ReEvaluator reEvaluator = ReEvaluator.agentDefaults(resultStore);
		ExperimentResult reEvaluated = reEvaluator.reEvaluate(original, juryWith(passingJudge()));

		ItemResult skipped = reEvaluated.items().get(0);
		assertThat(skipped.verdict().instrumentHash()).isEqualTo("original-instrument")
			.isNotEqualTo(reEvaluated.instrument().specHash());
		assertThat(io.github.markpollack.experiment.attestation.RunAttestation.of(reEvaluated)
			.items()
			.get(0)
			.attestability())
			.isEqualTo(io.github.markpollack.experiment.attestation.Attestability.VOTES_WITHOUT_ROSTER);
	}

	@Test
	void reJudgingClearsThePreviousVerdictsInstrumentFailure() {
		ItemResult brokenInstrument = createOriginalResult(true).items()
			.get(0)
			.toBuilder()
			.metadata(Map.of(io.github.markpollack.experiment.result.InstrumentRecord.ITEM_INSTRUMENT_FAILURE,
					"rosterMismatch",
					io.github.markpollack.experiment.result.InstrumentRecord.ITEM_INSTRUMENT_FAILURE_DETAIL,
					"jury: lists 3, voted 2"))
			.build();
		ExperimentResult original = experimentWith(List.of(brokenInstrument));
		resultStore.save(original);

		// Re-judging with a working jury is how such a run is repaired. If the old mark
		// travelled with the item, the repair could never take effect: the run would keep
		// counting an instrument failure and keep failing.
		ExperimentResult repaired = ReEvaluator.agentDefaults(resultStore)
			.reEvaluate(original, juryWith(passingJudge()));

		assertThat(repaired.items().get(0).metadata())
			.doesNotContainKey(io.github.markpollack.experiment.result.InstrumentRecord.ITEM_INSTRUMENT_FAILURE)
			.doesNotContainKey(io.github.markpollack.experiment.result.InstrumentRecord.ITEM_INSTRUMENT_FAILURE_DETAIL);
		assertThat(repaired.counts().instrumentFailures()).isZero();
	}

	// --- Helpers ---

	private ExperimentResult createOriginalResult(boolean passing) {
		InvocationResult invocation = InvocationResult.completed(List.of(), 1000, 500, 200, 0.05, 3000, "sess-1",
				Map.of());

		ItemResult item = ItemResult.builder()
			.itemId("ITEM-001")
			.itemSlug("test-item")
			.success(true)
			.passed(passing)
			.costUsd(0.05)
			.totalTokens(1700)
			.durationMs(3000)
			.scores(Map.of("original_judge", passing ? 1.0 : 0.0))
			.executionDetail(invocation)
			.build();

		return experimentWith(List.of(item));
	}

	private ExperimentResult experimentWith(List<ItemResult> items) {
		return ExperimentResult.builder()
			.experimentId("exp-" + java.util.UUID.randomUUID())
			.experimentName("test-experiment")
			.datasetSemanticVersion("1.0.0")
			.timestamp(Instant.now())
			.items(items)
			.totalCostUsd(0.05)
			.totalTokens(1700)
			.build();
	}

	private static Jury juryWith(Judge judge) {
		return SimpleJury.builder().judge(judge, 1.0).votingStrategy(new MajorityVotingStrategy()).build();
	}

	private static Judge passingJudge() {
		return ctx -> Judgment.pass("Passed");
	}

	private static Judge failingJudge() {
		return ctx -> Judgment.fail("Failed");
	}

}
