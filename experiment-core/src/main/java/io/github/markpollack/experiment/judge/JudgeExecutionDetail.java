package io.github.markpollack.experiment.judge;

import io.github.markpollack.experiment.result.ExecutionDetail;
import io.github.markpollack.judge.result.Judgment;

/**
 * Execution detail for judge experiments. Preserves the domain evidence for each item:
 * what the candidate judged, what was expected, and the scoring result.
 *
 * @param candidateJudgment the candidate judge's judgment
 * @param expectedLabel the expected label from the dataset
 * @param scorerResult the result of scoring actual vs expected
 */
public record JudgeExecutionDetail(Judgment candidateJudgment, String expectedLabel,
		JudgeScorerResult scorerResult) implements ExecutionDetail {

}
