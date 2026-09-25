package io.github.markpollack.experiment.judge;

import org.jspecify.annotations.Nullable;

/** A named comparison separate from the candidate finding and its operational verdict. */
public record JudgeComparison(String measureId, String scorerRevision, String kind, @Nullable String candidateSha256,
		@Nullable JudgeTruth truth, boolean scored, @Nullable Boolean match, @Nullable Double score,
		@Nullable String unscoredReason, String reasoning) {
	public JudgeComparison {
		if (measureId == null || measureId.isBlank() || scorerRevision == null || scorerRevision.isBlank())
			throw new IllegalArgumentException("Measure/scorer identity required");
		java.util.Objects.requireNonNull(kind);
		java.util.Objects.requireNonNull(reasoning);
		if (scored && (candidateSha256 == null || truth == null || match == null || score == null
				|| !Double.isFinite(score) || score < 0 || score > 1 || unscoredReason != null))
			throw new IllegalArgumentException("Invalid scored comparison");
		if (!scored && (match != null || score != null || unscoredReason == null || unscoredReason.isBlank()))
			throw new IllegalArgumentException("Invalid unscored comparison");
	}

	public static JudgeComparison unscored(String measure, String revision, String kind, @Nullable String candidate,
			@Nullable JudgeTruth truth, String reason, String explanation) {
		return new JudgeComparison(measure, revision, kind, candidate, truth, false, null, null, reason, explanation);
	}
}
