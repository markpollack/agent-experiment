package io.github.markpollack.experiment.judge;

import java.util.List;

import io.github.markpollack.experiment.result.ExperimentResult;
import org.jspecify.annotations.Nullable;

/**
 * Typed wrapper around {@link ExperimentResult} for judge experiments. Exposes
 * judge-specific conveniences while preserving compatibility with {@code ResultStore} and
 * {@code ComparisonEngine}.
 *
 * @param experimentResult the underlying experiment result
 * @param agreementRate fraction of scored items where the judge agreed with the expected
 * label, or <b>null when nothing was scored</b>. An experiment that scored nothing did
 * not disagree with everything, and reporting 0.0 for it would put it on a chart beside a
 * judge that got every item wrong
 * @param disagreements list of items where judge disagreed
 */
public record JudgeExperimentResult(ExperimentResult experimentResult, @Nullable Double agreementRate,
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
			// An item the judge did not decide is not a disagreement with it.
			.filter(item -> Boolean.FALSE.equals(item.passed()))
			.filter(item -> item.executionDetail() instanceof JudgeExecutionDetail)
			.map(item -> new JudgeDisagreement(item.itemId(), (JudgeExecutionDetail) item.executionDetail()))
			.toList();

		long scored = result.items().stream().filter(item -> item.passed() != null).count();
		Double agreement = scored == 0 ? null
				: (double) result.items().stream().filter(item -> Boolean.TRUE.equals(item.passed())).count() / scored;

		return new JudgeExperimentResult(result, agreement, disagreements);
	}

	/** For {@code ComparisonEngine} and {@code ResultStore} compatibility. */
	public ExperimentResult asExperimentResult() {
		return experimentResult;
	}

}
