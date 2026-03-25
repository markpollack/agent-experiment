package io.github.markpollack.experiment.scoring.claude;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for {@link SemanticDiffJudge}.
 *
 * @param model Claude model name (e.g. "sonnet", "haiku", "opus")
 * @param maxCriteriaToEvaluate maximum number of VERIFY criteria to evaluate per judgment
 * @param timeout timeout per criterion evaluation
 */
public record SemanticDiffJudgeConfig(String model, int maxCriteriaToEvaluate, Duration timeout) {

	private static final String DEFAULT_MODEL = "sonnet";

	private static final int DEFAULT_MAX_CRITERIA = 20;

	private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

	public SemanticDiffJudgeConfig {
		Objects.requireNonNull(model, "model must not be null");
		if (maxCriteriaToEvaluate <= 0) {
			throw new IllegalArgumentException("maxCriteriaToEvaluate must be positive");
		}
		Objects.requireNonNull(timeout, "timeout must not be null");
	}

	/**
	 * Create a config with all default values.
	 * @return default configuration
	 */
	public static SemanticDiffJudgeConfig defaults() {
		return new SemanticDiffJudgeConfig(DEFAULT_MODEL, DEFAULT_MAX_CRITERIA, DEFAULT_TIMEOUT);
	}

}
