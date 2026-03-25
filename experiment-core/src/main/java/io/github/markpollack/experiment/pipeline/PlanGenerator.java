package io.github.markpollack.experiment.pipeline;

/**
 * Generates an {@link ExecutionPlan} (Forge-style roadmap) from project analysis. May
 * invoke AI (e.g., a Claude session) to produce the plan.
 *
 * <p>
 * Contract:
 * <ul>
 * <li>May invoke AI: planning phase uses a separate Claude session</li>
 * <li>Blocking: returns when plan is complete</li>
 * <li>The returned {@link ExecutionPlan#roadmapMarkdown()} is the primary artifact</li>
 * </ul>
 */
public interface PlanGenerator {

	/**
	 * Generate an execution plan from the analysis and configuration.
	 * @param analysis structured project analysis (from {@link ProjectAnalyzer})
	 * @param config planning configuration (knowledge dir, target versions, tool paths)
	 * @return execution plan containing roadmap markdown and planning metrics
	 * @throws PlanGenerationException if plan generation fails
	 */
	ExecutionPlan generate(AnalysisEnvelope analysis, PlanConfig config) throws PlanGenerationException;

}
