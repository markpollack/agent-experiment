package io.github.markpollack.experiment.diagnostic;

import java.util.Objects;

/**
 * A single efficiency metric measurement with raw value, normalized score, and
 * human-readable detail.
 *
 * @param metric metric name (e.g., {@code "buildErrors"}, {@code "toolUtilization"})
 * @param rawValue raw measured value (e.g., 4 errors, 0.33 utilization ratio)
 * @param normalizedScore normalized to [0,1] where 1.0 = perfect efficiency
 * @param detail human-readable explanation of the measurement
 */
public record EfficiencyCheck(String metric, double rawValue, double normalizedScore, String detail) {

	public EfficiencyCheck {
		Objects.requireNonNull(metric, "metric");
		Objects.requireNonNull(detail, "detail");
	}

}
