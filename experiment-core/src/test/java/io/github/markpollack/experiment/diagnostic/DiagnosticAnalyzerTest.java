package io.github.markpollack.experiment.diagnostic;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.markpollack.experiment.agent.InvocationResult;
import io.github.markpollack.experiment.pipeline.ExecutionPlan;
import io.github.markpollack.experiment.result.ExperimentResult;
import io.github.markpollack.experiment.result.ItemResult;
import org.junit.jupiter.api.Test;
import org.springaicommunity.judge.jury.Verdict;
import org.springaicommunity.judge.result.Check;
import org.springaicommunity.judge.result.Judgment;
import org.springaicommunity.judge.result.JudgmentStatus;
import org.springaicommunity.judge.score.BooleanScore;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosticAnalyzerTest {

	private final DiagnosticAnalyzer analyzer = new DiagnosticAnalyzer(new HeuristicGapClassifier());

	// --- Single item ---

	@Test
	void analyzesSingleFailingItem() {
		ExperimentResult result = experimentWith(itemWithVerdict("item-1",
				verdictWithJudge("CommandJudge",
						Judgment.builder()
							.status(JudgmentStatus.FAIL)
							.score(new BooleanScore(false))
							.reasoning("Build failed")
							.check(Check.fail("command_execution", "exit 1"))
							.build())));

		DiagnosticReport report = analyzer.analyze(result);

		assertThat(report.experimentId()).isEqualTo("test-exp-1");
		assertThat(report.items()).hasSize(1);
		assertThat(report.items().get(0).itemId()).isEqualTo("item-1");
		assertThat(report.items().get(0).dominantGap()).isEqualTo(GapCategory.AGENT_EXECUTION_GAP);
		assertThat(report.distribution().totalChecks()).isEqualTo(1);
		assertThat(report.distribution().dominant()).isEqualTo(GapCategory.AGENT_EXECUTION_GAP);
	}

	@Test
	void analyzesSinglePassingItem() {
		ExperimentResult result = experimentWith(itemWithVerdict("item-1",
				verdictWithJudge("CommandJudge",
						Judgment.builder()
							.status(JudgmentStatus.PASS)
							.score(new BooleanScore(true))
							.reasoning("Build passed")
							.check(Check.pass("command_execution", "OK"))
							.build())));

		DiagnosticReport report = analyzer.analyze(result);

		assertThat(report.items()).hasSize(1);
		assertThat(report.items().get(0).checks()).isEmpty();
		assertThat(report.items().get(0).dominantGap()).isNull();
		assertThat(report.distribution().totalChecks()).isEqualTo(0);
		assertThat(report.recommendations()).contains("No failures detected — all judges passed.");
	}

	// --- Item with no verdict ---

	@Test
	void handlesItemWithNullVerdict() {
		ItemResult item = ItemResult.builder()
			.itemId("item-1")
			.itemSlug("test-item")
			.success(false)
			.passed(false)
			.build();

		ExperimentResult result = experimentWith(item);

		DiagnosticReport report = analyzer.analyze(result);

		assertThat(report.items()).hasSize(1);
		assertThat(report.items().get(0).checks()).isEmpty();
		assertThat(report.items().get(0).dominantGap()).isNull();
	}

	// --- Multiple items ---

	@Test
	void analyzesMultipleItems() {
		ItemResult item1 = itemWithVerdict("item-1",
				verdictWithJudge("CommandJudge",
						Judgment.builder()
							.status(JudgmentStatus.FAIL)
							.score(new BooleanScore(false))
							.reasoning("Build failed")
							.check(Check.fail("command_execution", "exit 1"))
							.build()));

		ItemResult item2 = itemWithVerdict("item-2",
				verdictWithJudge("TestInvarianceJudge", Judgment.abstain("No surefire reports")));

		ExperimentResult result = experimentWith(item1, item2);

		DiagnosticReport report = analyzer.analyze(result);

		assertThat(report.items()).hasSize(2);
		assertThat(report.distribution().totalChecks()).isEqualTo(2);
		assertThat(report.distribution().counts()).containsEntry(GapCategory.AGENT_EXECUTION_GAP, 1);
		assertThat(report.distribution().counts()).containsEntry(GapCategory.ANALYSIS_GAP, 1);
	}

	// --- Plan-aware classification ---

	@Test
	void usesExecutionPlanFromInvocationResult() {
		ExecutionPlan plan = new ExecutionPlan("RUN javax-to-jakarta", List.of(), List.of(), 0.0, 0, 0, 0, 0, null);
		InvocationResult invocation = new InvocationResult(true,
				io.github.markpollack.experiment.agent.TerminalStatus.COMPLETED, List.of(), 0, 0, 0, 0, 0, 0.0, 0, null,
				Map.of(), null, null, plan);

		Verdict verdict = verdictWithJudge("JavaxMigrationJudge",
				Judgment.builder()
					.status(JudgmentStatus.FAIL)
					.score(new BooleanScore(false))
					.reasoning("javax remaining")
					.check(Check.fail("javax.persistence removed", "38 imports"))
					.build());

		ItemResult item = ItemResult.builder()
			.itemId("item-1")
			.itemSlug("test-item")
			.success(true)
			.passed(false)
			.invocationResult(invocation)
			.verdict(verdict)
			.build();

		DiagnosticReport report = analyzer.analyze(experimentWith(item));

		assertThat(report.items().get(0).dominantGap()).isEqualTo(GapCategory.AGENT_EXECUTION_GAP);
		assertThat(report.items().get(0).checks().get(0).rationale()).contains("Plan covered");
	}

	// --- Recommendations ---

	@Test
	void generatesRecommendationForDominantGap() {
		ItemResult item1 = itemWithVerdict("item-1",
				verdictWithJudge("CommandJudge",
						Judgment.builder()
							.status(JudgmentStatus.FAIL)
							.score(new BooleanScore(false))
							.reasoning("Build failed")
							.check(Check.fail("cmd1", "fail"))
							.build()));

		ItemResult item2 = itemWithVerdict("item-2",
				verdictWithJudge("CommandJudge",
						Judgment.builder()
							.status(JudgmentStatus.FAIL)
							.score(new BooleanScore(false))
							.reasoning("Build failed 2")
							.check(Check.fail("cmd2", "fail"))
							.build()));

		DiagnosticReport report = analyzer.analyze(experimentWith(item1, item2));

		assertThat(report.recommendations()).anyMatch(r -> r.contains("AGENT_EXECUTION_GAP"));
		assertThat(report.recommendations()).anyMatch(r -> r.contains("100%"));
	}

	@Test
	void generatesRecommendationsForSecondaryGapsAbove20Percent() {
		ItemResult item1 = itemWithVerdict("item-1",
				verdictWithJudge("CommandJudge",
						Judgment.builder()
							.status(JudgmentStatus.FAIL)
							.score(new BooleanScore(false))
							.reasoning("fail")
							.check(Check.fail("cmd1", "fail"))
							.build()));

		ItemResult item2 = itemWithVerdict("item-2",
				verdictWithJudge("CommandJudge",
						Judgment.builder()
							.status(JudgmentStatus.FAIL)
							.score(new BooleanScore(false))
							.reasoning("fail")
							.check(Check.fail("cmd2", "fail"))
							.build()));

		ItemResult item3 = itemWithVerdict("item-3",
				verdictWithJudge("TestInvarianceJudge", Judgment.abstain("No surefire reports")));

		DiagnosticReport report = analyzer.analyze(experimentWith(item1, item2, item3));

		// 2 AGENT_EXECUTION_GAP, 1 ANALYSIS_GAP → 33% ANALYSIS_GAP >= 20% threshold
		assertThat(report.recommendations()).anyMatch(r -> r.contains("ANALYSIS_GAP"));
	}

	// --- GapDistribution ---

	@Test
	void gapDistributionComputesFractionsCorrectly() {
		List<DiagnosticCheck> checks = List.of(
				new DiagnosticCheck("J1", Check.fail("c1", "f"), GapCategory.AGENT_EXECUTION_GAP, "r1"),
				new DiagnosticCheck("J2", Check.fail("c2", "f"), GapCategory.AGENT_EXECUTION_GAP, "r2"),
				new DiagnosticCheck("J3", Check.fail("c3", "f"), GapCategory.PLAN_GENERATION_GAP, "r3"),
				new DiagnosticCheck("J4", Check.fail("c4", "f"), null, "unknown"));

		GapDistribution dist = GapDistribution.fromChecks(checks);

		assertThat(dist.totalChecks()).isEqualTo(3); // null-category excluded
		assertThat(dist.counts()).containsEntry(GapCategory.AGENT_EXECUTION_GAP, 2);
		assertThat(dist.counts()).containsEntry(GapCategory.PLAN_GENERATION_GAP, 1);
		assertThat(dist.fractions().get(GapCategory.AGENT_EXECUTION_GAP)).isCloseTo(0.667,
				org.assertj.core.data.Offset.offset(0.01));
		assertThat(dist.fractions().get(GapCategory.PLAN_GENERATION_GAP)).isCloseTo(0.333,
				org.assertj.core.data.Offset.offset(0.01));
		assertThat(dist.dominant()).isEqualTo(GapCategory.AGENT_EXECUTION_GAP);
	}

	@Test
	void gapDistributionHandlesEmptyChecks() {
		GapDistribution dist = GapDistribution.fromChecks(List.of());

		assertThat(dist.totalChecks()).isEqualTo(0);
		assertThat(dist.dominant()).isNull();
		assertThat(dist.counts()).isEmpty();
		assertThat(dist.fractions()).isEmpty();
	}

	// --- helpers ---

	private static Verdict verdictWithJudge(String judgeName, Judgment judgment) {
		return Verdict.builder()
			.aggregated(judgment)
			.individual(List.of(judgment))
			.individualByName(Map.of(judgeName, judgment))
			.build();
	}

	private static ItemResult itemWithVerdict(String itemId, Verdict verdict) {
		return ItemResult.builder()
			.itemId(itemId)
			.itemSlug(itemId)
			.success(true)
			.passed(false)
			.verdict(verdict)
			.build();
	}

	private static ExperimentResult experimentWith(ItemResult... items) {
		return ExperimentResult.builder()
			.experimentId("test-exp-1")
			.experimentName("test-experiment")
			.datasetSemanticVersion("1.0.0")
			.timestamp(Instant.now())
			.items(List.of(items))
			.build();
	}

}
