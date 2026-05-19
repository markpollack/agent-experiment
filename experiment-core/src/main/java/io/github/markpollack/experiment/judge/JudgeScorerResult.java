package io.github.markpollack.experiment.judge;

/**
 * Result of scoring a candidate judge's judgment against the expected label.
 *
 * @param match whether the judge agreed with the expected label
 * @param score normalized agreement score [0, 1]
 * @param reasoning explanation of why it matched or didn't
 */
public record JudgeScorerResult(boolean match, double score, String reasoning) {

}
