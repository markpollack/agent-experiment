package io.github.markpollack.experiment.diagnostic;

import io.github.markpollack.experiment.agent.InvocationResult;

/**
 * Evaluates the efficiency of an agent's execution trajectory, producing normalized [0,1]
 * scores for each metric plus a weighted composite.
 *
 * <p>
 * Contract:
 * <ul>
 * <li>Always produces a report, even with incomplete data (graceful degradation — missing
 * data results in the metric being omitted, not a failure)</li>
 * <li>Scores are normalized to [0,1] where 1.0 = perfect efficiency</li>
 * <li>Never throws — errors in metric computation result in the metric being excluded
 * with a warning logged</li>
 * <li>Does not modify any input data</li>
 * </ul>
 */
public interface EfficiencyEvaluator {

	/**
	 * Evaluate efficiency of a single agent invocation.
	 * @param result the agent's invocation result (cost, tokens, success status)
	 * @param context the full reasoning context (trajectory, analysis, plan, tools)
	 * @param config budget ceilings, metric weights, and thresholds
	 * @return efficiency report with per-metric scores and weighted composite
	 */
	EfficiencyReport evaluate(InvocationResult result, ReasoningContext context, EfficiencyConfig config);

}
