package io.github.markpollack.experiment.scoring;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.markpollack.judge.jury.Verdict;
import io.github.markpollack.judge.result.Judgment;

/**
 * Extracts per-judge normalized scores from a {@link Verdict}.
 *
 * <p>
 * Uses Agent Judge 0.14's {@link Judgment#effectiveScore()} so measured normalized scores
 * are retained, Boolean PASS/FAIL outcomes project to 1.0/0.0, and ABSTAIN/ERROR remain
 * absent rather than being manufactured as zeroes.
 */
public final class VerdictExtractor {

	private VerdictExtractor() {
	}

	/**
	 * Extract per-judge normalized [0, 1] scores from a verdict.
	 * @param verdict the jury verdict containing individual judgments
	 * @return map of judge name to normalized score; judges with non-normalizable scores
	 * are omitted
	 */
	public static Map<String, Double> extractScores(Verdict verdict) {
		Map<String, Double> scores = new LinkedHashMap<>();
		extractScoresRecursive(verdict, scores);
		return Map.copyOf(scores);
	}

	private static void extractScoresRecursive(Verdict verdict, Map<String, Double> scores) {
		// Extract from successful composite attempts first (configuration order).
		for (var attempt : verdict.compositeAttempts()) {
			if (attempt.verdict() != null) {
				extractScoresRecursive(attempt.verdict(), scores);
			}
		}
		// Then extract from this verdict's own individual judgments
		for (Map.Entry<String, Judgment> entry : verdict.individualByName().entrySet()) {
			var effectiveScore = entry.getValue().effectiveScore();
			if (effectiveScore.isPresent()) {
				scores.put(entry.getKey(), effectiveScore.getAsDouble());
			}
		}
	}

	/**
	 * Check whether the verdict's aggregated judgment passed.
	 * @param verdict the jury verdict
	 * @return {@code true} if the aggregated judgment passed
	 */
	public static boolean passed(Verdict verdict) {
		return verdict.aggregated().pass();
	}

}
