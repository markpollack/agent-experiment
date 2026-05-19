package io.github.markpollack.experiment.judge;

/**
 * A single disagreement between the candidate judge and the expected label.
 *
 * @param itemId the dataset item ID
 * @param detail the judge execution detail for the disagreement
 */
public record JudgeDisagreement(String itemId, JudgeExecutionDetail detail) {

}
