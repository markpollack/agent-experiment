package io.github.markpollack.experiment.judge;

import org.jspecify.annotations.Nullable;

/**
 * Result of scoring a candidate judge's judgment against the expected label.
 *
 * <p>
 * Three outcomes, not two. A candidate that made no measurement — it errored, found the
 * criterion not applicable, or abstained without a score — gives the scorer nothing to
 * compare, and that is neither agreement nor disagreement.
 *
 * <p>
 * Substituting a number there does not merely record a wrong score. Against an expected
 * label of {@code "0"} a fabricated zero reads as a <em>correct</em> agreement, so an
 * unmeasured judgment would <em>raise</em> the agreement rate.
 *
 * @param match whether the judge agreed with the expected label, or null when there was
 * nothing to compare
 * @param score normalized agreement score [0, 1], or null when unscored
 * @param reasoning explanation of why it matched, did not match, or could not be scored
 */
public record JudgeScorerResult(@Nullable Boolean match, @Nullable Double score, String reasoning) {

	public JudgeScorerResult {
		java.util.Objects.requireNonNull(reasoning, "reasoning must not be null");
		if ((match == null) != (score == null)) {
			throw new IllegalArgumentException(
					"a scored result needs both a match and a score; an unscored result has neither");
		}
		if (score != null && (!Double.isFinite(score) || score < 0.0 || score > 1.0)) {
			throw new IllegalArgumentException("score must be finite and between 0.0 and 1.0");
		}
	}

	/** The candidate produced nothing to compare against the expected label. */
	public static JudgeScorerResult unscored(String reasoning) {
		return new JudgeScorerResult(null, null, reasoning);
	}

	/**
	 * True when the scorer could actually compare the candidate with what was expected.
	 */
	public boolean scored() {
		return this.match != null;
	}

}
