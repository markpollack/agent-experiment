package io.github.markpollack.experiment.pipeline;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.markpollack.experiment.agent.AgentInvocationException;
import io.github.markpollack.experiment.agent.AgentInvoker;
import io.github.markpollack.experiment.agent.InvocationContext;
import io.github.markpollack.experiment.agent.InvocationResult;
import io.github.markpollack.journal.claude.PhaseCapture;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composite {@link AgentInvoker} that orchestrates the pipeline: analyze, plan, execute.
 * Wraps a delegate AgentInvoker (typically ClaudeSdkInvoker) for the execution phase.
 *
 * <p>
 * All phases are optional — null analyzer skips analysis, null planner skips planning. At
 * minimum, the delegate is always invoked (with an optionally enriched prompt).
 *
 * <p>
 * Graceful degradation: analysis failure logs a warning and skips analysis. Planning
 * failure logs a warning and skips planning. Only execution failures propagate.
 */
public class PipelineAgentInvoker implements AgentInvoker {

	private static final Logger logger = LoggerFactory.getLogger(PipelineAgentInvoker.class);

	private final @Nullable ProjectAnalyzer analyzer;

	private final @Nullable PlanGenerator planner;

	private final PipelineConfig pipelineConfig;

	private final AgentInvoker delegate;

	/**
	 * @param analyzer project analyzer (nullable — skips analysis if null)
	 * @param planner plan generator (nullable — skips planning if null)
	 * @param pipelineConfig pipeline-level configuration
	 * @param delegate the AgentInvoker to use for the execution phase
	 */
	public PipelineAgentInvoker(@Nullable ProjectAnalyzer analyzer, @Nullable PlanGenerator planner,
			PipelineConfig pipelineConfig, AgentInvoker delegate) {
		this.analyzer = analyzer;
		this.planner = planner;
		this.pipelineConfig = Objects.requireNonNull(pipelineConfig, "pipelineConfig must not be null");
		this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
	}

	@Override
	public InvocationResult invoke(InvocationContext context) throws AgentInvocationException {
		// Phase 1: Analyze
		@Nullable
		AnalysisEnvelope analysis = null;
		if (analyzer != null && pipelineConfig.enableAnalysis()) {
			analysis = runAnalysis(context.workspacePath());
		}

		// Phase 2: Plan
		@Nullable
		ExecutionPlan plan = null;
		if (planner != null && pipelineConfig.enablePlanning()) {
			plan = runPlanning(analysis, context.model(), context.runDir());
		}

		// Phase 3: Enrich prompt and execute
		InvocationContext enrichedContext = enrichContext(context, analysis, plan);
		InvocationResult executionResult = delegate.invoke(enrichedContext);

		// Aggregate metrics from planning + execution
		return aggregateResults(analysis, plan, executionResult);
	}

	private @Nullable AnalysisEnvelope runAnalysis(Path workspace) {
		try {
			logger.info("Running project analysis on {}", workspace);
			AnalysisConfig analysisConfig = AnalysisConfig.pomOnly(pipelineConfig.targetBootVersion(),
					pipelineConfig.targetJavaVersion());
			AnalysisEnvelope envelope = analyzer.analyze(workspace, analysisConfig);
			logger.info("Analysis complete: project={}, bootVersion={}, {} dependencies, {} import patterns",
					envelope.projectName(), envelope.bootVersion(), envelope.dependencies().size(),
					envelope.importPatterns().size());
			return envelope;
		}
		catch (AnalysisException ex) {
			logger.warn("Analysis failed, skipping: {}", ex.getMessage());
			return null;
		}
	}

	private @Nullable ExecutionPlan runPlanning(@Nullable AnalysisEnvelope analysis, String executionModel,
			@Nullable Path runDir) {
		try {
			String model = pipelineConfig.planningModel() != null ? pipelineConfig.planningModel() : executionModel;
			PlanConfig planConfig = new PlanConfig(pipelineConfig.knowledgeDir(), pipelineConfig.toolPaths(),
					pipelineConfig.targetBootVersion(), pipelineConfig.targetJavaVersion(), model,
					pipelineConfig.effectivePlanningTimeout(), runDir);

			if (analysis == null) {
				// No analysis available — planner gets a minimal empty envelope
				analysis = AnalysisEnvelope.builder().projectName("unknown").buildTool("unknown").build();
				logger.info("Planning without analysis data (analysis was skipped or failed)");
			}

			logger.info("Generating execution plan with model {}", model);
			ExecutionPlan plan = planner.generate(analysis, planConfig);
			logger.info("Plan generated: {} chars, {} tools recommended, cost=${}, duration={}ms",
					plan.roadmapMarkdown().length(), plan.toolRecommendations().size(),
					String.format("%.4f", plan.planningCostUsd()), plan.planningDurationMs());
			return plan;
		}
		catch (PlanGenerationException ex) {
			logger.warn("Planning failed, skipping: {}", ex.getMessage());
			return null;
		}
	}

	private static final String PLAN_MODE_PREVENTION = "IMPORTANT: Do not enter plan mode. "
			+ "Do not use the EnterPlanMode or ExitPlanMode tools. "
			+ "An execution roadmap has already been generated for you in a prior planning session. "
			+ "Execute the provided roadmap directly. Begin making changes immediately.";

	InvocationContext enrichContext(InvocationContext original, @Nullable AnalysisEnvelope analysis,
			@Nullable ExecutionPlan plan) {
		String enrichedPrompt = buildEnrichedPrompt(original.prompt(), analysis, plan);
		String systemPrompt = buildSystemPrompt(original.systemPrompt(), plan);
		if (enrichedPrompt.equals(original.prompt())
				&& java.util.Objects.equals(systemPrompt, original.systemPrompt())) {
			return original;
		}
		return InvocationContext.builder()
			.workspacePath(original.workspacePath())
			.prompt(enrichedPrompt)
			.systemPrompt(systemPrompt)
			.model(original.model())
			.timeout(original.timeout())
			.metadata(original.metadata())
			.runDir(original.runDir())
			.build();
	}

	@Nullable
	String buildSystemPrompt(@Nullable String originalSystemPrompt, @Nullable ExecutionPlan plan) {
		if (plan == null) {
			return originalSystemPrompt;
		}
		if (originalSystemPrompt == null || originalSystemPrompt.isBlank()) {
			return PLAN_MODE_PREVENTION;
		}
		return originalSystemPrompt + "\n\n" + PLAN_MODE_PREVENTION;
	}

	String buildEnrichedPrompt(String originalPrompt, @Nullable AnalysisEnvelope analysis,
			@Nullable ExecutionPlan plan) {
		if (analysis == null && plan == null) {
			return originalPrompt;
		}

		StringBuilder enriched = new StringBuilder(originalPrompt);

		if (plan != null) {
			enriched.append("\n\n---\n\n## Execution Roadmap\n\n");
			enriched.append(plan.roadmapMarkdown());
		}

		if (!pipelineConfig.toolPaths().isEmpty()) {
			enriched.append("\n\n## Available Tools\n\n");
			for (Map.Entry<String, Path> entry : pipelineConfig.toolPaths().entrySet()) {
				enriched.append("- **")
					.append(entry.getKey())
					.append("**: `java -jar ")
					.append(entry.getValue())
					.append("`\n");
			}
		}

		if (analysis != null) {
			enriched.append("\n\n## Analysis Summary\n\n");
			enriched.append(formatAnalysisSummary(analysis));
		}

		return enriched.toString();
	}

	private String formatAnalysisSummary(AnalysisEnvelope analysis) {
		StringBuilder sb = new StringBuilder();
		sb.append("- **Project**: ").append(analysis.projectName()).append('\n');
		if (analysis.bootVersion() != null) {
			sb.append("- **Spring Boot version**: ").append(analysis.bootVersion()).append('\n');
		}
		if (analysis.javaVersion() != null) {
			sb.append("- **Java version**: ").append(analysis.javaVersion()).append('\n');
		}
		sb.append("- **Build tool**: ").append(analysis.buildTool()).append('\n');
		if (analysis.parentCoordinates() != null) {
			sb.append("- **Parent**: ").append(analysis.parentCoordinates()).append('\n');
		}
		if (!analysis.dependencies().isEmpty()) {
			sb.append("- **Dependencies**: ").append(analysis.dependencies().size()).append(" declared\n");
		}
		if (!analysis.importPatterns().isEmpty()) {
			sb.append("- **Import patterns found**:\n");
			for (Map.Entry<String, List<String>> entry : analysis.importPatterns().entrySet()) {
				sb.append("  - `")
					.append(entry.getKey())
					.append("` in ")
					.append(entry.getValue().size())
					.append(" files\n");
			}
		}
		if (!analysis.configFiles().isEmpty()) {
			sb.append("- **Config files**: ").append(String.join(", ", analysis.configFiles())).append('\n');
		}
		return sb.toString();
	}

	private InvocationResult aggregateResults(@Nullable AnalysisEnvelope analysis, @Nullable ExecutionPlan plan,
			InvocationResult executionResult) {
		if (plan == null && analysis == null) {
			return executionResult;
		}

		if (plan == null) {
			// Analysis-only: attach analysis without changing metrics
			return new InvocationResult(executionResult.success(), executionResult.status(), executionResult.phases(),
					executionResult.inputTokens(), executionResult.outputTokens(), executionResult.thinkingTokens(),
					executionResult.cacheCreationInputTokens(), executionResult.cacheReadInputTokens(),
					executionResult.totalCostUsd(), executionResult.durationMs(), executionResult.sessionId(),
					executionResult.metadata(), executionResult.errorMessage(), analysis, null);
		}

		// Combine planning + execution metrics
		int totalInput = plan.planningInputTokens() + executionResult.inputTokens();
		int totalOutput = plan.planningOutputTokens() + executionResult.outputTokens();
		int totalThinking = plan.planningThinkingTokens() + executionResult.thinkingTokens();
		double totalCost = plan.planningCostUsd() + executionResult.totalCostUsd();
		long totalDuration = plan.planningDurationMs() + executionResult.durationMs();

		// Create a synthetic PhaseCapture for the planning phase
		// PhaseCapture(phaseName, promptText, inputTokens, outputTokens, thinkingTokens,
		// durationMs, apiDurationMs, totalCostUsd, sessionId, numTurns, isError,
		// textOutput, thinkingBlocks, toolUses, rawResult)
		PhaseCapture planningCapture = new PhaseCapture("planning", "planning", plan.planningInputTokens(),
				plan.planningOutputTokens(), plan.planningThinkingTokens(), plan.planningDurationMs(), 0L,
				plan.planningCostUsd(), plan.planningSessionId(), 0, false, plan.roadmapMarkdown(), null, null, null);

		List<PhaseCapture> allPhases = new ArrayList<>();
		allPhases.add(planningCapture);
		allPhases.addAll(executionResult.phases());

		// Preserve planning details in metadata
		Map<String, String> enrichedMetadata = new HashMap<>(executionResult.metadata());
		enrichedMetadata.put("pipeline.planningCostUsd", String.format("%.6f", plan.planningCostUsd()));
		enrichedMetadata.put("pipeline.planningDurationMs", String.valueOf(plan.planningDurationMs()));
		enrichedMetadata.put("pipeline.planningTokens", String.valueOf(plan.planningTotalTokens()));
		enrichedMetadata.put("pipeline.toolRecommendations", String.join(",", plan.toolRecommendations()));

		int totalCacheCreation = executionResult.cacheCreationInputTokens();
		int totalCacheRead = executionResult.cacheReadInputTokens();

		return new InvocationResult(executionResult.success(), executionResult.status(), allPhases, totalInput,
				totalOutput, totalThinking, totalCacheCreation, totalCacheRead, totalCost, totalDuration,
				executionResult.sessionId(), enrichedMetadata, executionResult.errorMessage(), analysis, plan);
	}

}
