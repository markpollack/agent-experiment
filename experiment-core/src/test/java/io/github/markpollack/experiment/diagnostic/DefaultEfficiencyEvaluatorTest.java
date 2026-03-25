package io.github.markpollack.experiment.diagnostic;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.markpollack.journal.claude.PhaseCapture;
import io.github.markpollack.journal.claude.ToolResultRecord;
import org.junit.jupiter.api.Test;

import io.github.markpollack.experiment.agent.InvocationResult;
import io.github.markpollack.experiment.pipeline.ExecutionPlan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DefaultEfficiencyEvaluatorTest {

	private final DefaultEfficiencyEvaluator evaluator = new DefaultEfficiencyEvaluator();

	private final EfficiencyConfig defaults = EfficiencyConfig.defaults();

	// --- M-1: Build Errors ---

	@Test
	void buildErrors_zeroErrors_perfectScore() {
		ReasoningContext context = contextWithPhases(List.of());
		InvocationResult result = completedResult(0.0);

		EfficiencyReport report = evaluator.evaluate(result, context, defaults);

		assertThat(scoreFor(report, "buildErrors")).isEqualTo(1.0);
	}

	@Test
	void buildErrors_atThreshold_zeroScore() {
		// 8 errors with threshold 8 → score 0.0
		List<ToolResultRecord> errors = errorResults(8);
		ReasoningContext context = contextWithPhases(List.of(phaseWithErrors(errors)));
		InvocationResult result = completedResult(0.0);

		EfficiencyReport report = evaluator.evaluate(result, context, defaults);

		assertThat(scoreFor(report, "buildErrors")).isEqualTo(0.0);
	}

	@Test
	void buildErrors_intermediate_linearDecay() {
		// 4 errors with threshold 8 → score 0.5
		List<ToolResultRecord> errors = errorResults(4);
		ReasoningContext context = contextWithPhases(List.of(phaseWithErrors(errors)));
		InvocationResult result = completedResult(0.0);

		EfficiencyReport report = evaluator.evaluate(result, context, defaults);

		assertThat(scoreFor(report, "buildErrors")).isCloseTo(0.5, within(0.001));
	}

	@Test
	void buildErrors_aboveThreshold_clampedToZero() {
		List<ToolResultRecord> errors = errorResults(12);
		ReasoningContext context = contextWithPhases(List.of(phaseWithErrors(errors)));
		InvocationResult result = completedResult(0.0);

		EfficiencyReport report = evaluator.evaluate(result, context, defaults);

		assertThat(scoreFor(report, "buildErrors")).isEqualTo(0.0);
	}

	// --- M-2: Tool Utilization ---

	@Test
	void toolUtilization_allToolsUsed_perfectScore() {
		ExecutionPlan plan = plan(List.of("pom-upgrader", "javax-to-jakarta"));
		ReasoningContext context = context(null, plan, Set.of("pom-upgrader", "javax-to-jakarta"));
		InvocationResult result = completedResult(0.0);

		EfficiencyReport report = evaluator.evaluate(result, context, defaults);

		assertThat(scoreFor(report, "toolUtilization")).isEqualTo(1.0);
	}

	@Test
	void toolUtilization_noToolsUsed_zeroScore() {
		ExecutionPlan plan = plan(List.of());
		ReasoningContext context = context(null, plan, Set.of("pom-upgrader", "javax-to-jakarta"));
		InvocationResult result = completedResult(0.0);

		EfficiencyReport report = evaluator.evaluate(result, context, defaults);

		assertThat(scoreFor(report, "toolUtilization")).isEqualTo(0.0);
	}

	@Test
	void toolUtilization_emptyAvailableTools_perfectScore() {
		ExecutionPlan plan = plan(List.of());
		ReasoningContext context = context(null, plan, Set.of());
		InvocationResult result = completedResult(0.0);

		EfficiencyReport report = evaluator.evaluate(result, context, defaults);

		assertThat(scoreFor(report, "toolUtilization")).isEqualTo(1.0);
	}

	@Test
	void toolUtilization_nullPlan_excluded() {
		ReasoningContext context = context(null, null, Set.of("pom-upgrader", "javax-to-jakarta"));
		InvocationResult result = completedResult(0.0);

		EfficiencyReport report = evaluator.evaluate(result, context, defaults);

		assertThat(report.scores()).doesNotContainKey("efficiency.toolUtilization");
	}

	@Test
	void toolUtilization_partialUse() {
		// 1 of 3 tools used → 0.333
		ExecutionPlan plan = plan(List.of("javax-to-jakarta"));
		ReasoningContext context = context(null, plan,
				Set.of("pom-upgrader", "javax-to-jakarta", "thymeleaf-migrator"));
		InvocationResult result = completedResult(0.0);

		EfficiencyReport report = evaluator.evaluate(result, context, defaults);

		assertThat(scoreFor(report, "toolUtilization")).isCloseTo(0.333, within(0.01));
	}

	// --- M-3: Cost ---

	@Test
	void cost_zeroCost_perfectScore() {
		ReasoningContext context = emptyContext();
		InvocationResult result = completedResult(0.0);

		EfficiencyReport report = evaluator.evaluate(result, context, defaults);

		assertThat(scoreFor(report, "cost")).isEqualTo(1.0);
	}

	@Test
	void cost_atCeiling_zeroScore() {
		ReasoningContext context = emptyContext();
		InvocationResult result = completedResult(5.0);

		EfficiencyReport report = evaluator.evaluate(result, context, defaults);

		assertThat(scoreFor(report, "cost")).isEqualTo(0.0);
	}

	@Test
	void cost_aboveCeiling_clampedToZero() {
		ReasoningContext context = emptyContext();
		InvocationResult result = completedResult(10.0);

		EfficiencyReport report = evaluator.evaluate(result, context, defaults);

		assertThat(scoreFor(report, "cost")).isEqualTo(0.0);
	}

	@Test
	void cost_intermediate() {
		// $2.50 of $5.00 ceiling → 0.5
		ReasoningContext context = emptyContext();
		InvocationResult result = completedResult(2.5);

		EfficiencyReport report = evaluator.evaluate(result, context, defaults);

		assertThat(scoreFor(report, "cost")).isCloseTo(0.5, within(0.001));
	}

	// --- M-4: Recovery Cycles ---

	@Test
	void recoveryCycles_sameRootCause_singleCluster() {
		// Two errors with same [ERROR] signal → 1 cluster
		ToolResultRecord e1 = new ToolResultRecord("t1", "[ERROR] cannot find symbol: class Foo\nmore text", true);
		ToolResultRecord e2 = new ToolResultRecord("t2", "[ERROR] cannot find symbol: class Foo\ndifferent text", true);
		ReasoningContext context = contextWithPhases(List.of(phaseWithErrors(List.of(e1, e2))));
		InvocationResult result = completedResult(0.0);

		EfficiencyReport report = evaluator.evaluate(result, context, defaults);

		EfficiencyCheck check = checkFor(report, "recoveryCycles");
		assertThat(check.rawValue()).isEqualTo(1.0); // 1 cluster
		assertThat(check.normalizedScore()).isCloseTo(0.875, within(0.001)); // 1 - 1/8
	}

	@Test
	void recoveryCycles_distinctCauses_multipleClusters() {
		ToolResultRecord e1 = new ToolResultRecord("t1", "[ERROR] cannot find symbol: class Foo", true);
		ToolResultRecord e2 = new ToolResultRecord("t2", "[ERROR] package jakarta.xml.bind does not exist", true);
		ToolResultRecord e3 = new ToolResultRecord("t3", "[ERROR] Formatting violations found", true);
		ReasoningContext context = contextWithPhases(List.of(phaseWithErrors(List.of(e1, e2, e3))));
		InvocationResult result = completedResult(0.0);

		EfficiencyReport report = evaluator.evaluate(result, context, defaults);

		EfficiencyCheck check = checkFor(report, "recoveryCycles");
		assertThat(check.rawValue()).isEqualTo(3.0); // 3 distinct clusters
	}

	@Test
	void recoveryCycles_noErrors_perfectScore() {
		ReasoningContext context = contextWithPhases(List.of());
		InvocationResult result = completedResult(0.0);

		EfficiencyReport report = evaluator.evaluate(result, context, defaults);

		assertThat(scoreFor(report, "recoveryCycles")).isEqualTo(1.0);
	}

	// --- Composite ---

	@Test
	void composite_weightedAverage() {
		// Custom weights: only buildErrors and cost, equal weight
		EfficiencyConfig config = new EfficiencyConfig(10.0, Map.of("buildErrors", 0.5, "cost", 0.5), 10);

		// 5 errors / 10 threshold → buildErrors = 0.5
		// $5 / $10 ceiling → cost = 0.5
		List<ToolResultRecord> errors = errorResults(5);
		ReasoningContext context = contextWithPhases(List.of(phaseWithErrors(errors)));
		InvocationResult result = completedResult(5.0);

		EfficiencyReport report = evaluator.evaluate(result, context, config);

		assertThat(report.compositeScore()).isCloseTo(0.5, within(0.001));
	}

	// --- Score Prefix ---

	@Test
	void allScoreKeys_prefixedWithEfficiency() {
		ExecutionPlan plan = plan(List.of("pom-upgrader"));
		ReasoningContext context = context(null, plan, Set.of("pom-upgrader"));
		InvocationResult result = completedResult(1.0);

		EfficiencyReport report = evaluator.evaluate(result, context, defaults);

		assertThat(report.scores().keySet()).allMatch(k -> k.startsWith("efficiency."));
		assertThat(report.scores()).containsKey("efficiency.composite");
	}

	// --- Graceful Degradation ---

	@Test
	void allMetricsExcluded_compositeIsOne() {
		// No weights match any metric name → composite 1.0
		EfficiencyConfig config = new EfficiencyConfig(5.0, Map.of("nonexistent", 1.0), 8);
		ReasoningContext context = emptyContext();
		InvocationResult result = completedResult(0.0);

		EfficiencyReport report = evaluator.evaluate(result, context, config);

		assertThat(report.compositeScore()).isEqualTo(1.0);
	}

	// --- Failed Invocation ---

	@Test
	void failedInvocation_efficiencyStillComputed() {
		// Failed invocation with cost and errors — efficiency should still produce scores
		List<ToolResultRecord> errors = errorResults(3);
		PhaseCapture phase = phaseWithErrors(errors);
		InvocationResult result = new InvocationResult(false,
				io.github.markpollack.experiment.agent.TerminalStatus.ERROR, List.of(phase), 100, 200, 50, 0, 0, 3.0,
				5000, null, Map.of(), "Build failed", null, null);
		ReasoningContext context = contextWithPhases(List.of(phase));

		EfficiencyReport report = evaluator.evaluate(result, context, defaults);

		assertThat(report.scores()).containsKey("efficiency.buildErrors");
		assertThat(report.scores()).containsKey("efficiency.cost");
		assertThat(report.scores()).containsKey("efficiency.recoveryCycles");
		assertThat(report.scores()).containsKey("efficiency.composite");
		assertThat(scoreFor(report, "cost")).isCloseTo(0.4, within(0.001)); // $3 / $5
	}

	// --- Error signal clustering ---

	@Test
	void countErrorClusters_emptyContent_countsAsOwnCluster() {
		ToolResultRecord e1 = new ToolResultRecord("t1", "", true);
		ToolResultRecord e2 = new ToolResultRecord("t2", "[ERROR] real error", true);

		int clusters = evaluator.countErrorClusters(List.of(e1, e2));

		assertThat(clusters).isEqualTo(2);
	}

	// --- helpers ---

	private static InvocationResult completedResult(double costUsd) {
		return InvocationResult.completed(List.of(), 0, 0, 0, costUsd, 0, null, Map.of());
	}

	private static ReasoningContext emptyContext() {
		return new ReasoningContext(null, null, Set.of(), List.of(), null, null, List.of(), null, null);
	}

	private static ReasoningContext contextWithPhases(List<PhaseCapture> phases) {
		return new ReasoningContext(null, null, Set.of(), phases, null, null, List.of(), null, null);
	}

	private static ReasoningContext context(@SuppressWarnings("unused") Object analysis, ExecutionPlan plan,
			Set<String> availableTools) {
		return new ReasoningContext(null, plan, availableTools, List.of(), null, null, List.of(), null, null);
	}

	private static ExecutionPlan plan(List<String> toolRecommendations) {
		return new ExecutionPlan("roadmap text", toolRecommendations, List.of(), 0.0, 0, 0, 0, 0, null);
	}

	private static PhaseCapture phaseWithErrors(List<ToolResultRecord> toolResults) {
		return new PhaseCapture("execute", "prompt", 0, 0, 0, 0, 0, 0, 0, 0.0, "session-1", 1, false, "", List.of(),
				List.of(), null, toolResults);
	}

	private static List<ToolResultRecord> errorResults(int count) {
		List<ToolResultRecord> results = new java.util.ArrayList<>();
		for (int i = 0; i < count; i++) {
			results.add(new ToolResultRecord("t" + i, "[ERROR] error-signal-" + i + ": something failed", true));
		}
		return results;
	}

	private static double scoreFor(EfficiencyReport report, String metric) {
		Double score = report.scores().get(DefaultEfficiencyEvaluator.PREFIX + metric);
		assertThat(score).as("Score for " + metric).isNotNull();
		return score;
	}

	private static EfficiencyCheck checkFor(EfficiencyReport report, String metric) {
		return report.checks()
			.stream()
			.filter(c -> c.metric().equals(metric))
			.findFirst()
			.orElseThrow(() -> new AssertionError("No check for metric: " + metric));
	}

}
