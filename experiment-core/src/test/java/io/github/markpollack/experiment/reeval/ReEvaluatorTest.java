package io.github.markpollack.experiment.reeval;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.markpollack.experiment.agent.InvocationResult;
import io.github.markpollack.experiment.result.ExperimentResult;
import io.github.markpollack.experiment.result.ItemResult;
import io.github.markpollack.experiment.store.InMemoryResultStore;
import io.github.markpollack.judge.Judge;
import io.github.markpollack.judge.context.JudgmentContext;
import io.github.markpollack.judge.jury.Jury;
import io.github.markpollack.judge.jury.MajorityVotingStrategy;
import io.github.markpollack.judge.jury.SimpleJury;
import io.github.markpollack.judge.result.Judgment;
import io.github.markpollack.judge.result.JudgmentStatus;
import io.github.markpollack.judge.score.BooleanScore;
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
		assertThat(reEvaluated.passRate()).isEqualTo(0.0);
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
		return ctx -> Judgment.builder()
			.score(new BooleanScore(true))
			.status(JudgmentStatus.PASS)
			.reasoning("Passed")
			.build();
	}

	private static Judge failingJudge() {
		return ctx -> Judgment.builder()
			.score(new BooleanScore(false))
			.status(JudgmentStatus.FAIL)
			.reasoning("Failed")
			.build();
	}

}
