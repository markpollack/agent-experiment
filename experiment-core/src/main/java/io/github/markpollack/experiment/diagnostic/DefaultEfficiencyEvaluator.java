package io.github.markpollack.experiment.diagnostic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.markpollack.journal.claude.ToolResultRecord;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.markpollack.experiment.agent.InvocationResult;

/**
 * Default efficiency evaluator computing 4 metrics from agent trajectory data:
 *
 * <ul>
 * <li><b>M-1 buildErrors</b>: error tool results in trajectory</li>
 * <li><b>M-2 toolUtilization</b>: fraction of available tools selected by planner</li>
 * <li><b>M-3 cost</b>: agent cost relative to budget ceiling</li>
 * <li><b>M-4 recoveryCycles</b>: distinct root-cause error clusters</li>
 * </ul>
 *
 * <p>
 * All scores normalized to [0,1] where 1.0 = perfect efficiency. Metrics are excluded
 * (not scored 0.0) when required data is missing. Score keys are prefixed with
 * {@code efficiency.}.
 */
public class DefaultEfficiencyEvaluator implements EfficiencyEvaluator {

	private static final Logger logger = LoggerFactory.getLogger(DefaultEfficiencyEvaluator.class);

	static final String PREFIX = "efficiency.";

	@Override
	public EfficiencyReport evaluate(InvocationResult result, ReasoningContext context, EfficiencyConfig config) {
		List<EfficiencyCheck> checks = new ArrayList<>();

		computeBuildErrors(context, config, checks);
		computeToolUtilization(context, checks);
		computeCost(result, config, checks);
		computeRecoveryCycles(context, config, checks);

		Map<String, Double> scores = new LinkedHashMap<>();
		for (EfficiencyCheck check : checks) {
			scores.put(PREFIX + check.metric(), check.normalizedScore());
		}

		double composite = computeComposite(checks, config);
		scores.put(PREFIX + "composite", composite);

		return new EfficiencyReport(checks, scores, composite);
	}

	/**
	 * M-1: Build errors. Linear decay from 1.0 (0 errors) to 0.0 (≥ threshold errors).
	 */
	private void computeBuildErrors(ReasoningContext context, EfficiencyConfig config, List<EfficiencyCheck> checks) {
		try {
			List<ToolResultRecord> errors = context.errorToolResults();
			int errorCount = errors.size();
			double score = Math.max(0.0, 1.0 - (double) errorCount / config.errorCountThreshold());
			checks.add(new EfficiencyCheck("buildErrors", errorCount, score,
					errorCount + " error tool result(s) out of " + config.errorCountThreshold() + " threshold"));
		}
		catch (Exception ex) {
			logger.warn("Failed to compute buildErrors metric: {}", ex.getMessage());
		}
	}

	/**
	 * M-2: Tool utilization. Fraction of available tools that the planner selected.
	 * Excluded when plan is null (missing data, not planner quality signal) or analysis
	 * is null.
	 */
	private void computeToolUtilization(ReasoningContext context, List<EfficiencyCheck> checks) {
		try {
			if (context.plan() == null) {
				logger.debug("Skipping toolUtilization — plan is null");
				return;
			}
			if (context.availableTools().isEmpty()) {
				checks.add(new EfficiencyCheck("toolUtilization", 1.0, 1.0, "No tools available — perfect by default"));
				return;
			}
			int available = context.availableTools().size();
			int unused = context.unusedTools().size();
			double utilization = 1.0 - (double) unused / available;
			checks.add(new EfficiencyCheck("toolUtilization", utilization, utilization,
					(available - unused) + " of " + available + " tools selected by planner"));
		}
		catch (Exception ex) {
			logger.warn("Failed to compute toolUtilization metric: {}", ex.getMessage());
		}
	}

	/**
	 * M-3: Cost efficiency. Linear decay from 1.0 ($0) to 0.0 (≥ ceiling).
	 */
	private void computeCost(InvocationResult result, EfficiencyConfig config, List<EfficiencyCheck> checks) {
		try {
			double costUsd = result.totalCostUsd();
			if (costUsd <= 0) {
				checks.add(new EfficiencyCheck("cost", costUsd, 1.0, "Zero or negative cost — perfect"));
				return;
			}
			double score = Math.max(0.0, 1.0 - costUsd / config.costCeilingUsd());
			checks.add(new EfficiencyCheck("cost", costUsd, score,
					String.format("$%.4f of $%.2f ceiling", costUsd, config.costCeilingUsd())));
		}
		catch (Exception ex) {
			logger.warn("Failed to compute cost metric: {}", ex.getMessage());
		}
	}

	/**
	 * M-4: Recovery cycles. Distinct root-cause error clusters in the trajectory. Same
	 * normalization as buildErrors but counts clusters rather than raw errors.
	 */
	private void computeRecoveryCycles(ReasoningContext context, EfficiencyConfig config,
			List<EfficiencyCheck> checks) {
		try {
			List<ToolResultRecord> errors = context.errorToolResults();
			if (errors.isEmpty()) {
				checks.add(new EfficiencyCheck("recoveryCycles", 0, 1.0, "No error tool results — zero cycles"));
				return;
			}
			int clusters = countErrorClusters(errors);
			double score = Math.max(0.0, 1.0 - (double) clusters / config.errorCountThreshold());
			checks.add(new EfficiencyCheck("recoveryCycles", clusters, score,
					clusters + " distinct error cluster(s) from " + errors.size() + " errors"));
		}
		catch (Exception ex) {
			logger.warn("Failed to compute recoveryCycles metric: {}", ex.getMessage());
		}
	}

	/**
	 * Cluster errors by signal similarity. Two errors with the same extracted signal
	 * (first meaningful [ERROR] line) are considered the same root cause.
	 */
	int countErrorClusters(List<ToolResultRecord> errors) {
		Set<String> signals = new LinkedHashSet<>();
		for (ToolResultRecord error : errors) {
			String content = error.content();
			if (content != null && !content.isBlank()) {
				String signal = extractErrorSignal(content);
				if (signal != null) {
					signals.add(signal);
				}
			}
			else {
				// No content — count as its own cluster
				signals.add("unknown-" + signals.size());
			}
		}
		return Math.max(1, signals.size());
	}

	/**
	 * Extract the most informative signal from Maven build error output. Mirrors the
	 * approach in {@code DeterministicReasoner.extractErrorSignal()}.
	 */
	@Nullable
	static String extractErrorSignal(String content) {
		for (String line : content.split("\n")) {
			String trimmed = line.trim();
			if (trimmed.startsWith("[ERROR]") && !trimmed.contains("See ") && !trimmed.contains("Re-run")
					&& !trimmed.contains("For more information") && !trimmed.contains("-> [Help")
					&& trimmed.length() > 10) {
				return trimmed.substring(0, Math.min(150, trimmed.length()));
			}
		}
		return content.substring(0, Math.min(100, content.length())).replace("\n", " ");
	}

	/**
	 * Weighted average of check scores using config weights. Weights are normalized to
	 * sum to 1.0. If no checks have weights, returns 1.0 (no evidence of inefficiency).
	 */
	private double computeComposite(List<EfficiencyCheck> checks, EfficiencyConfig config) {
		double weightedSum = 0.0;
		double totalWeight = 0.0;

		for (EfficiencyCheck check : checks) {
			Double weight = config.metricWeights().get(check.metric());
			if (weight != null && weight > 0) {
				weightedSum += check.normalizedScore() * weight;
				totalWeight += weight;
			}
		}

		if (totalWeight == 0.0) {
			return 1.0;
		}
		return weightedSum / totalWeight;
	}

}
