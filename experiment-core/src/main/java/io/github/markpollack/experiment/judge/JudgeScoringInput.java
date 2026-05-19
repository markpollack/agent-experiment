package io.github.markpollack.experiment.judge;

import io.github.markpollack.experiment.dataset.DatasetItem;
import io.github.markpollack.judge.result.Judgment;

/**
 * Input to a {@link JudgeScorer}: the dataset item, the candidate judge's actual
 * judgment, and the expected label from the dataset.
 *
 * @param item the dataset item (available for advanced scorers needing item-level
 * context)
 * @param actual the candidate judge's judgment
 * @param expectedLabel the expected label from the dataset
 */
public record JudgeScoringInput(DatasetItem item, Judgment actual, String expectedLabel) {

}
