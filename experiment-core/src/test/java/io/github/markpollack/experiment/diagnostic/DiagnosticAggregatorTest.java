package io.github.markpollack.experiment.diagnostic;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springaicommunity.judge.result.Check;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosticAggregatorTest {

	private final DiagnosticAggregator aggregator = new DiagnosticAggregator();

	// --- Empty input ---

	@Test
	void aggregatesEmptyList() {
		AggregatedDiagnostic result = aggregator.aggregate(List.of());

		assertThat(result.runCount()).isEqualTo(0);
		assertThat(result.overallDistribution().totalChecks()).isEqualTo(0);
		assertThat(result.stabilityFraction()).isEqualTo(1.0);
	}

	// --- Single run ---

	@Test
	void aggregatesSingleRun() {
		DiagnosticReport report = reportWith("run-1", itemDiag("item-1", GapCategory.AGENT_EXECUTION_GAP, "cmd fail"),
				itemDiag("item-2", GapCategory.PLAN_GENERATION_GAP, "plan miss"));

		AggregatedDiagnostic result = aggregator.aggregate(List.of(report));

		assertThat(result.runCount()).isEqualTo(1);
		assertThat(result.overallDistribution().totalChecks()).isEqualTo(2);
		assertThat(result.stochasticItems()).isEmpty();
		assertThat(result.stableItems()).containsExactlyInAnyOrder("item-1", "item-2");
		assertThat(result.stabilityFraction()).isEqualTo(1.0);
		assertThat(result.recommendations()).anyMatch(r -> r.contains("Only 1 run"));
	}

	// --- Multiple runs — stable items ---

	@Test
	void detectsStableItemsAcrossRuns() {
		DiagnosticReport run1 = reportWith("run-1", itemDiag("item-1", GapCategory.AGENT_EXECUTION_GAP, "cmd fail"));

		DiagnosticReport run2 = reportWith("run-2",
				itemDiag("item-1", GapCategory.AGENT_EXECUTION_GAP, "cmd fail again"));

		DiagnosticReport run3 = reportWith("run-3",
				itemDiag("item-1", GapCategory.AGENT_EXECUTION_GAP, "cmd fail third"));

		AggregatedDiagnostic result = aggregator.aggregate(List.of(run1, run2, run3));

		assertThat(result.runCount()).isEqualTo(3);
		assertThat(result.stableItems()).containsExactly("item-1");
		assertThat(result.stochasticItems()).isEmpty();
		assertThat(result.stabilityFraction()).isEqualTo(1.0);
	}

	// --- Multiple runs — stochastic items ---

	@Test
	void detectsStochasticItems() {
		DiagnosticReport run1 = reportWith("run-1", itemDiag("item-1", GapCategory.AGENT_EXECUTION_GAP, "cmd fail"));

		DiagnosticReport run2 = reportWith("run-2", itemDiag("item-1", GapCategory.PLAN_GENERATION_GAP, "plan miss"));

		AggregatedDiagnostic result = aggregator.aggregate(List.of(run1, run2));

		assertThat(result.stochasticItems()).containsExactly("item-1");
		assertThat(result.stableItems()).isEmpty();
		assertThat(result.stabilityFraction()).isEqualTo(0.0);
		assertThat(result.recommendations()).anyMatch(r -> r.contains("stochastic behavior"));
	}

	// --- Mixed stable and stochastic ---

	@Test
	void mixedStabilityAcrossItems() {
		DiagnosticReport run1 = reportWith("run-1", itemDiag("item-1", GapCategory.AGENT_EXECUTION_GAP, "fail"),
				itemDiag("item-2", GapCategory.PLAN_GENERATION_GAP, "miss"));

		DiagnosticReport run2 = reportWith("run-2", itemDiag("item-1", GapCategory.AGENT_EXECUTION_GAP, "fail again"),
				itemDiag("item-2", GapCategory.AGENT_EXECUTION_GAP, "different gap"));

		AggregatedDiagnostic result = aggregator.aggregate(List.of(run1, run2));

		assertThat(result.stableItems()).containsExactly("item-1");
		assertThat(result.stochasticItems()).containsExactly("item-2");
		assertThat(result.stabilityFraction()).isEqualTo(0.5);
	}

	// --- Per-run distributions ---

	@Test
	void tracksPerRunDistributions() {
		DiagnosticReport run1 = reportWith("run-1", itemDiag("item-1", GapCategory.AGENT_EXECUTION_GAP, "fail"));

		DiagnosticReport run2 = reportWith("run-2", itemDiag("item-1", GapCategory.PLAN_GENERATION_GAP, "miss"));

		AggregatedDiagnostic result = aggregator.aggregate(List.of(run1, run2));

		assertThat(result.perRunDistributions()).hasSize(2);
		assertThat(result.perRunDistributions().get("run-1").dominant()).isEqualTo(GapCategory.AGENT_EXECUTION_GAP);
		assertThat(result.perRunDistributions().get("run-2").dominant()).isEqualTo(GapCategory.PLAN_GENERATION_GAP);
	}

	// --- Overall distribution ---

	@Test
	void computesOverallDistribution() {
		DiagnosticReport run1 = reportWith("run-1", itemDiag("item-1", GapCategory.AGENT_EXECUTION_GAP, "fail"),
				itemDiag("item-2", GapCategory.AGENT_EXECUTION_GAP, "fail"));

		DiagnosticReport run2 = reportWith("run-2", itemDiag("item-1", GapCategory.AGENT_EXECUTION_GAP, "fail"),
				itemDiag("item-2", GapCategory.PLAN_GENERATION_GAP, "miss"));

		AggregatedDiagnostic result = aggregator.aggregate(List.of(run1, run2));

		assertThat(result.overallDistribution().totalChecks()).isEqualTo(4);
		assertThat(result.overallDistribution().counts()).containsEntry(GapCategory.AGENT_EXECUTION_GAP, 3);
		assertThat(result.overallDistribution().counts()).containsEntry(GapCategory.PLAN_GENERATION_GAP, 1);
		assertThat(result.overallDistribution().dominant()).isEqualTo(GapCategory.AGENT_EXECUTION_GAP);
	}

	// --- Recommendations ---

	@Test
	void recommendsNGreaterThan3() {
		DiagnosticReport run1 = reportWith("run-1", itemDiag("item-1", GapCategory.AGENT_EXECUTION_GAP, "fail"));

		AggregatedDiagnostic result = aggregator.aggregate(List.of(run1));

		assertThat(result.recommendations()).anyMatch(r -> r.contains("N>=3"));
	}

	@Test
	void noNWarningForThreeOrMoreRuns() {
		DiagnosticReport run1 = reportWith("run-1", itemDiag("item-1", GapCategory.AGENT_EXECUTION_GAP, "fail"));
		DiagnosticReport run2 = reportWith("run-2", itemDiag("item-1", GapCategory.AGENT_EXECUTION_GAP, "fail"));
		DiagnosticReport run3 = reportWith("run-3", itemDiag("item-1", GapCategory.AGENT_EXECUTION_GAP, "fail"));

		AggregatedDiagnostic result = aggregator.aggregate(List.of(run1, run2, run3));

		assertThat(result.recommendations()).noneMatch(r -> r.contains("N>=3"));
	}

	// --- helpers ---

	private static ItemDiagnostic itemDiag(String itemId, GapCategory gap, String rationale) {
		DiagnosticCheck check = new DiagnosticCheck("TestJudge", Check.fail("test_check", rationale), gap, rationale);
		return new ItemDiagnostic(itemId, List.of(check), gap);
	}

	private static DiagnosticReport reportWith(String experimentId, ItemDiagnostic... items) {
		List<DiagnosticCheck> allChecks = new java.util.ArrayList<>();
		for (ItemDiagnostic item : items) {
			allChecks.addAll(item.checks());
		}
		return new DiagnosticReport(experimentId, List.of(items), GapDistribution.fromChecks(allChecks), List.of());
	}

}
