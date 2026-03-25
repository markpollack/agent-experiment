package io.github.markpollack.experiment.pipeline;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.github.markpollack.experiment.agent.AgentInvocationException;
import io.github.markpollack.experiment.agent.InvocationContext;
import io.github.markpollack.experiment.agent.InvocationResult;
import io.github.markpollack.experiment.agent.MockAgentInvoker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineAgentInvokerTest {

	@TempDir
	Path workspace;

	@Test
	void fullPipelineAnalyzePlanExecute() throws Exception {
		AnalysisEnvelope envelope = sampleEnvelope();
		ExecutionPlan plan = samplePlan();
		StubProjectAnalyzer analyzer = new StubProjectAnalyzer(envelope);
		StubPlanGenerator planner = new StubPlanGenerator(plan);
		MockAgentInvoker delegate = new MockAgentInvoker();
		PipelineConfig config = PipelineConfig.builder()
			.toolPaths(Map.of("javax-to-jakarta", Path.of("/tools/j2j.jar")))
			.targetBootVersion("3.0.0")
			.build();

		PipelineAgentInvoker invoker = new PipelineAgentInvoker(analyzer, planner, config, delegate);
		InvocationResult result = invoker.invoke(buildContext());

		assertThat(result.success()).isTrue();
		assertThat(delegate.invocationCount()).isEqualTo(1);
		// Verify prompt was enriched
		InvocationContext delegateContext = delegate.invocations().get(0);
		String enrichedPrompt = delegateContext.prompt();
		assertThat(enrichedPrompt).contains("## Execution Roadmap");
		assertThat(enrichedPrompt).contains("## Available Tools");
		assertThat(enrichedPrompt).contains("javax-to-jakarta");
		assertThat(enrichedPrompt).contains("## Analysis Summary");
		assertThat(enrichedPrompt).contains("petclinic");
		// Verify system prompt includes plan mode prevention
		assertThat(delegateContext.systemPrompt()).contains("Do not enter plan mode");
		assertThat(delegateContext.systemPrompt()).contains("Do not use the EnterPlanMode");
	}

	@Test
	void skipAnalysisWhenAnalyzerNull() throws Exception {
		ExecutionPlan plan = samplePlan();
		StubPlanGenerator planner = new StubPlanGenerator(plan);
		MockAgentInvoker delegate = new MockAgentInvoker();
		PipelineConfig config = PipelineConfig.builder().build();

		PipelineAgentInvoker invoker = new PipelineAgentInvoker(null, planner, config, delegate);
		InvocationResult result = invoker.invoke(buildContext());

		assertThat(result.success()).isTrue();
		assertThat(delegate.invocationCount()).isEqualTo(1);
		// Planner still called (with minimal envelope)
		assertThat(planner.invokedCount).isEqualTo(1);
	}

	@Test
	void skipPlanningWhenPlannerNull() throws Exception {
		AnalysisEnvelope envelope = sampleEnvelope();
		StubProjectAnalyzer analyzer = new StubProjectAnalyzer(envelope);
		MockAgentInvoker delegate = new MockAgentInvoker();
		PipelineConfig config = PipelineConfig.builder().build();

		PipelineAgentInvoker invoker = new PipelineAgentInvoker(analyzer, null, config, delegate);
		InvocationResult result = invoker.invoke(buildContext());

		assertThat(result.success()).isTrue();
		// Prompt enriched with analysis summary only (no roadmap section)
		String prompt = delegate.invocations().get(0).prompt();
		assertThat(prompt).contains("## Analysis Summary");
		assertThat(prompt).doesNotContain("## Execution Roadmap");
	}

	@Test
	void passThroughWhenBothNull() throws Exception {
		MockAgentInvoker delegate = new MockAgentInvoker();
		PipelineConfig config = PipelineConfig.builder().build();

		PipelineAgentInvoker invoker = new PipelineAgentInvoker(null, null, config, delegate);
		InvocationResult result = invoker.invoke(buildContext());

		assertThat(result.success()).isTrue();
		// Prompt unchanged
		String prompt = delegate.invocations().get(0).prompt();
		assertThat(prompt).isEqualTo("Do the upgrade task");
	}

	@Test
	void analysisFailureGracefullySkips() throws Exception {
		ProjectAnalyzer failingAnalyzer = (w, c) -> {
			throw new AnalysisException("POM not found");
		};
		StubPlanGenerator planner = new StubPlanGenerator(samplePlan());
		MockAgentInvoker delegate = new MockAgentInvoker();
		PipelineConfig config = PipelineConfig.builder().build();

		PipelineAgentInvoker invoker = new PipelineAgentInvoker(failingAnalyzer, planner, config, delegate);
		InvocationResult result = invoker.invoke(buildContext());

		assertThat(result.success()).isTrue();
		assertThat(planner.invokedCount).isEqualTo(1);
		assertThat(delegate.invocationCount()).isEqualTo(1);
	}

	@Test
	void planningFailureGracefullySkips() throws Exception {
		StubProjectAnalyzer analyzer = new StubProjectAnalyzer(sampleEnvelope());
		PlanGenerator failingPlanner = (a, c) -> {
			throw new PlanGenerationException("Claude session timed out");
		};
		MockAgentInvoker delegate = new MockAgentInvoker();
		PipelineConfig config = PipelineConfig.builder().build();

		PipelineAgentInvoker invoker = new PipelineAgentInvoker(analyzer, failingPlanner, config, delegate);
		InvocationResult result = invoker.invoke(buildContext());

		assertThat(result.success()).isTrue();
		// Prompt enriched with analysis only (planning failed)
		String prompt = delegate.invocations().get(0).prompt();
		assertThat(prompt).contains("## Analysis Summary");
		assertThat(prompt).doesNotContain("## Execution Roadmap");
	}

	@Test
	void metricAggregationCombinesPlanningAndExecution() throws Exception {
		ExecutionPlan plan = new ExecutionPlan("# Roadmap", List.of("tool1"), List.of(), 0.05, 500, 1000, 200, 10000,
				"plan-session");
		StubPlanGenerator planner = new StubPlanGenerator(plan);
		MockAgentInvoker delegate = new MockAgentInvoker();
		PipelineConfig config = PipelineConfig.builder().build();

		PipelineAgentInvoker invoker = new PipelineAgentInvoker(null, planner, config, delegate);
		InvocationResult result = invoker.invoke(buildContext());

		// Delegate returns: 100 input, 200 output, 50 thinking, $0.01, 1000ms
		// Plan adds: 500 input, 1000 output, 200 thinking, $0.05, 10000ms
		assertThat(result.inputTokens()).isEqualTo(600);
		assertThat(result.outputTokens()).isEqualTo(1200);
		assertThat(result.thinkingTokens()).isEqualTo(250);
		assertThat(result.totalCostUsd()).isCloseTo(0.06, org.assertj.core.data.Offset.offset(0.0001));
		assertThat(result.durationMs()).isEqualTo(11000);
		// Planning phase added as first phase
		assertThat(result.phases()).hasSize(1); // delegate returns 0 phases + 1 planning
		assertThat(result.phases().get(0).phaseName()).isEqualTo("planning");
		// Metadata includes pipeline details
		assertThat(result.metadata()).containsKey("pipeline.planningCostUsd");
		assertThat(result.metadata()).containsKey("pipeline.toolRecommendations");
	}

	@Test
	void promptEnrichmentContentVerification() throws Exception {
		AnalysisEnvelope envelope = AnalysisEnvelope.builder()
			.projectName("petclinic")
			.bootVersion("2.7.3")
			.javaVersion("1.8")
			.buildTool("maven")
			.parentCoordinates("org.springframework.boot:spring-boot-starter-parent:2.7.3")
			.dependencies(Map.of("spring-boot-starter-web", "2.7.3"))
			.importPatterns(Map.of("javax.persistence", List.of("Owner.java", "Pet.java")))
			.configFiles(List.of("application.properties"))
			.build();
		ExecutionPlan plan = new ExecutionPlan("## Step 1\n- RUN javax-to-jakarta", List.of("javax-to-jakarta"),
				List.of(), 0.0, 0, 0, 0, 0, null);

		PipelineConfig config = PipelineConfig.builder()
			.toolPaths(Map.of("javax-to-jakarta", Path.of("/tools/j2j.jar")))
			.build();

		PipelineAgentInvoker invoker = new PipelineAgentInvoker(null, null, config, new MockAgentInvoker());
		String enriched = invoker.buildEnrichedPrompt("Original prompt", envelope, plan);

		assertThat(enriched).startsWith("Original prompt");
		assertThat(enriched).contains("## Execution Roadmap");
		assertThat(enriched).contains("RUN javax-to-jakarta");
		assertThat(enriched).contains("## Available Tools");
		assertThat(enriched).contains("java -jar /tools/j2j.jar");
		assertThat(enriched).contains("## Analysis Summary");
		assertThat(enriched).contains("Spring Boot version**: 2.7.3");
		assertThat(enriched).contains("Java version**: 1.8");
		assertThat(enriched).contains("javax.persistence");
		assertThat(enriched).contains("2 files");
	}

	@Test
	void disableAnalysisViaConfig() throws Exception {
		StubProjectAnalyzer analyzer = new StubProjectAnalyzer(sampleEnvelope());
		MockAgentInvoker delegate = new MockAgentInvoker();
		PipelineConfig config = PipelineConfig.builder().enableAnalysis(false).build();

		PipelineAgentInvoker invoker = new PipelineAgentInvoker(analyzer, null, config, delegate);
		invoker.invoke(buildContext());

		assertThat(analyzer.invokedCount).isEqualTo(0);
	}

	@Test
	void disablePlanningViaConfig() throws Exception {
		StubPlanGenerator planner = new StubPlanGenerator(samplePlan());
		MockAgentInvoker delegate = new MockAgentInvoker();
		PipelineConfig config = PipelineConfig.builder().enablePlanning(false).build();

		PipelineAgentInvoker invoker = new PipelineAgentInvoker(null, planner, config, delegate);
		invoker.invoke(buildContext());

		assertThat(planner.invokedCount).isEqualTo(0);
	}

	@Test
	void systemPromptPreventsPlanModeWhenPlanPresent() throws Exception {
		ExecutionPlan plan = samplePlan();
		StubPlanGenerator planner = new StubPlanGenerator(plan);
		MockAgentInvoker delegate = new MockAgentInvoker();
		PipelineConfig config = PipelineConfig.builder().build();

		PipelineAgentInvoker invoker = new PipelineAgentInvoker(null, planner, config, delegate);
		invoker.invoke(buildContext());

		InvocationContext delegateContext = delegate.invocations().get(0);
		assertThat(delegateContext.systemPrompt()).contains("Do not enter plan mode");
		assertThat(delegateContext.systemPrompt()).contains("Execute the provided roadmap directly");
	}

	@Test
	void systemPromptPreservedWhenNoPlan() throws Exception {
		MockAgentInvoker delegate = new MockAgentInvoker();
		PipelineConfig config = PipelineConfig.builder().build();

		PipelineAgentInvoker invoker = new PipelineAgentInvoker(null, null, config, delegate);
		InvocationContext contextWithSystem = InvocationContext.builder()
			.workspacePath(workspace)
			.prompt("Do the upgrade task")
			.systemPrompt("You are a helpful assistant")
			.model("sonnet")
			.timeout(Duration.ofMinutes(5))
			.metadata(Map.of("itemId", "TEST-001"))
			.build();
		invoker.invoke(contextWithSystem);

		// No plan = no plan mode prevention, original system prompt preserved
		InvocationContext delegateContext = delegate.invocations().get(0);
		assertThat(delegateContext.systemPrompt()).isEqualTo("You are a helpful assistant");
	}

	@Test
	void systemPromptAppendedToExistingWhenPlanPresent() throws Exception {
		ExecutionPlan plan = samplePlan();
		StubPlanGenerator planner = new StubPlanGenerator(plan);
		MockAgentInvoker delegate = new MockAgentInvoker();
		PipelineConfig config = PipelineConfig.builder().build();

		PipelineAgentInvoker invoker = new PipelineAgentInvoker(null, planner, config, delegate);
		InvocationContext contextWithSystem = InvocationContext.builder()
			.workspacePath(workspace)
			.prompt("Do the upgrade task")
			.systemPrompt("You are a helpful assistant")
			.model("sonnet")
			.timeout(Duration.ofMinutes(5))
			.metadata(Map.of("itemId", "TEST-001"))
			.build();
		invoker.invoke(contextWithSystem);

		InvocationContext delegateContext = delegate.invocations().get(0);
		assertThat(delegateContext.systemPrompt()).startsWith("You are a helpful assistant");
		assertThat(delegateContext.systemPrompt()).contains("Do not enter plan mode");
	}

	@Test
	void delegateExceptionPropagates() {
		MockAgentInvoker delegate = new MockAgentInvoker().defaultError("Claude crashed");
		PipelineConfig config = PipelineConfig.builder().build();

		PipelineAgentInvoker invoker = new PipelineAgentInvoker(null, null, config, delegate);

		assertThatThrownBy(() -> invoker.invoke(buildContext())).isInstanceOf(AgentInvocationException.class)
			.hasMessageContaining("Claude crashed");
	}

	private InvocationContext buildContext() {
		return InvocationContext.builder()
			.workspacePath(workspace)
			.prompt("Do the upgrade task")
			.model("sonnet")
			.timeout(Duration.ofMinutes(5))
			.metadata(Map.of("itemId", "TEST-001"))
			.build();
	}

	private AnalysisEnvelope sampleEnvelope() {
		return AnalysisEnvelope.builder()
			.projectName("petclinic")
			.bootVersion("2.7.3")
			.javaVersion("1.8")
			.buildTool("maven")
			.dependencies(Map.of("spring-boot-starter-web", "2.7.3"))
			.importPatterns(Map.of("javax.persistence", List.of("Owner.java")))
			.build();
	}

	private ExecutionPlan samplePlan() {
		return new ExecutionPlan("# Roadmap\n\n## Step 1\n- RUN javax-to-jakarta --apply", List.of("javax-to-jakarta"),
				List.of(), 0.02, 300, 600, 100, 5000, "plan-session-1");
	}

	/** Stub ProjectAnalyzer for testing. */
	static class StubProjectAnalyzer implements ProjectAnalyzer {

		final AnalysisEnvelope result;

		int invokedCount = 0;

		StubProjectAnalyzer(AnalysisEnvelope result) {
			this.result = result;
		}

		@Override
		public AnalysisEnvelope analyze(Path workspace, AnalysisConfig config) {
			invokedCount++;
			return result;
		}

	}

	/** Stub PlanGenerator for testing. */
	static class StubPlanGenerator implements PlanGenerator {

		final ExecutionPlan result;

		int invokedCount = 0;

		StubPlanGenerator(ExecutionPlan result) {
			this.result = result;
		}

		@Override
		public ExecutionPlan generate(AnalysisEnvelope analysis, PlanConfig config) {
			invokedCount++;
			return result;
		}

	}

}
