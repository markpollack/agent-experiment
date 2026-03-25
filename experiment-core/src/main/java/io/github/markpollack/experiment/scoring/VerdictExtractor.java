package io.github.markpollack.experiment.scoring;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springaicommunity.judge.jury.Verdict;
import org.springaicommunity.judge.result.Judgment;
import org.springaicommunity.judge.score.BooleanScore;
import org.springaicommunity.judge.score.NumericalScore;
import org.springaicommunity.judge.score.Score;

/**
 * Extracts per-judge normalized scores from a {@link Verdict}.
 *
 * <p>
 * Normalizes each judge's score to the [0, 1] range for cross-run comparison:
 * <ul>
 * <li>{@link BooleanScore}: {@code true} &rarr; 1.0, {@code false} &rarr; 0.0</li>
 * <li>{@link NumericalScore}: delegates to {@link NumericalScore#normalized()}</li>
 * </ul>
 * Scores that cannot be normalized (e.g., {@code CategoricalScore}) are skipped.
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
		// Extract from sub-verdicts first (earlier tiers come first)
		for (Verdict sub : verdict.subVerdicts()) {
			extractScoresRecursive(sub, scores);
		}
		// Then extract from this verdict's own individual judgments
		for (Map.Entry<String, Judgment> entry : verdict.individualByName().entrySet()) {
			Double normalized = normalizeScore(entry.getValue().score());
			if (normalized != null) {
				scores.put(entry.getKey(), normalized);
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

	private static Double normalizeScore(Score score) {
		if (score instanceof BooleanScore booleanScore) {
			return booleanScore.value() ? 1.0 : 0.0;
		}
		else if (score instanceof NumericalScore numericalScore) {
			return numericalScore.normalized();
		}
		return null;
	}

}
