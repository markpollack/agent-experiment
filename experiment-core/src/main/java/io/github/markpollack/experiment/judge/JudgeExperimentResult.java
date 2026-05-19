package io.github.markpollack.experiment.judge;

import java.util.List;

import io.github.markpollack.experiment.result.ExperimentResult;
import io.github.markpollack.experiment.result.ItemResult;

/**
 * Typed wrapper around {@link ExperimentResult} for judge experiments. Exposes
 * judge-specific conveniences while preserving compatibility with {@code ResultStore} and
 * {@code ComparisonEngine}.
 *
 * @param experimentResult the underlying experiment result
 * @param agreementRate fraction of items where judge agreed with expected label
 * @param disagreements list of items where judge disagreed
 */
public record JudgeExperimentResult(ExperimentResult experimentResult, double agreementRate,
		List<JudgeDisagreement> disagreements) {

	/**
	 * Create from an {@link ExperimentResult} containing {@link JudgeExecutionDetail}
	 * items.
	 * @param result the experiment result
	 * @return a judge-typed result with computed agreement rate and disagreement list
	 */
	public static JudgeExperimentResult from(ExperimentResult result) {
		List<JudgeDisagreement> disagreements = result.items()
			.stream()
			.filter(item -> !item.passed())
			.filter(item -> item.executionDetail() instanceof JudgeExecutionDetail)
			.map(item -> new JudgeDisagreement(item.itemId(), (JudgeExecutionDetail) item.executionDetail()))
			.toList();

		double agreement = result.items().isEmpty() ? 0.0
				: (double) result.items().stream().filter(ItemResult::passed).count() / result.items().size();

		return new JudgeExperimentResult(result, agreement, disagreements);
	}

	/** For {@code ComparisonEngine} and {@code ResultStore} compatibility. */
	public ExperimentResult asExperimentResult() {
		return experimentResult;
	}

}
