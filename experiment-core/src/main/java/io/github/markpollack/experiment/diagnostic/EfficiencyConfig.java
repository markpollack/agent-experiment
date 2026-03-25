package io.github.markpollack.experiment.diagnostic;

import java.util.Map;

/**
 * Configuration for efficiency evaluation: budget ceilings, metric weights, and
 * thresholds.
 *
 * @param costCeilingUsd maximum expected cost per item; costs at or above this normalize
 * to 0.0
 * @param metricWeights weight per metric name for composite calculation; weights are
 * normalized internally to sum to 1.0
 * @param errorCountThreshold number of build errors at which the buildErrors score
 * reaches 0.0
 */
public record EfficiencyConfig(double costCeilingUsd, Map<String, Double> metricWeights, int errorCountThreshold) {

	public EfficiencyConfig {
		if (costCeilingUsd <= 0) {
			throw new IllegalArgumentException("costCeilingUsd must be positive: " + costCeilingUsd);
		}
		if (errorCountThreshold <= 0) {
			throw new IllegalArgumentException("errorCountThreshold must be positive: " + errorCountThreshold);
		}
		metricWeights = Map.copyOf(metricWeights);
	}

	/**
	 * Default configuration: $5 ceiling, 4 metrics with weights summing to 1.0, error
	 * threshold of 8.
	 */
	public static EfficiencyConfig defaults() {
		return new EfficiencyConfig(5.0,
				Map.of("buildErrors", 0.35, "toolUtilization", 0.25, "cost", 0.20, "recoveryCycles", 0.20), 8);
	}

}
