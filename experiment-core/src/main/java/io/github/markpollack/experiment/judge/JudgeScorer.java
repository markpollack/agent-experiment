package io.github.markpollack.experiment.judge;

/**
 * Scores a candidate judge's {@link io.github.markpollack.judge.judgment.Judgment}
 * against the expected label from the dataset.
 *
 * <p>
 * Built-in implementations use only {@code actual} + {@code expectedLabel}. The
 * {@link JudgeScoringInput#item()} is available for advanced scorers needing item-level
 * context (per-item rubrics, difficulty-adjusted thresholds).
 */
@FunctionalInterface
public interface JudgeScorer {

	default String measureId() {
		return null;
	}

	default String scorerRevision() {
		return null;
	}

	default String truthKind() {
		return "STATUS_LABEL";
	}

	/**
	 * Score the candidate judge's judgment against the expected label.
	 * @param input the scoring input containing dataset item, actual judgment, and
	 * expected label
	 * @return the scoring result with match flag, normalized score, and reasoning
	 */
	JudgeScorerResult score(JudgeScoringInput input);

}
