package io.github.markpollack.experiment.judge;

import io.github.markpollack.judge.score.CategoricalScore;
import io.github.markpollack.judge.score.NumericalScore;

/**
 * Built-in {@link JudgeScorer} implementations.
 */
public final class JudgeScorers {

	private JudgeScorers() {
	}

	/** Judge PASS/FAIL must exactly match expected "PASS"/"FAIL" label. */
	public static JudgeScorer exactVerdictMatch() {
		return input -> {
			boolean match = input.actual().status().name().equalsIgnoreCase(input.expectedLabel());
			return new JudgeScorerResult(match, match ? 1.0 : 0.0,
					"Expected " + input.expectedLabel() + ", got " + input.actual().status());
		};
	}

	/** CategoricalScore value must match expected label (case-insensitive). */
	public static JudgeScorer exactCategoryMatch() {
		return input -> {
			String actualCategory = input.actual().score() instanceof CategoricalScore cs ? cs.value()
					: input.actual().status().name();
			boolean match = actualCategory.equalsIgnoreCase(input.expectedLabel());
			return new JudgeScorerResult(match, match ? 1.0 : 0.0,
					"Expected category '" + input.expectedLabel() + "', got '" + actualCategory + "'");
		};
	}

	/** NumericalScore must be within tolerance of expected numeric value. */
	public static JudgeScorer numericalTolerance(double tolerance) {
		return input -> {
			double expectedVal;
			try {
				expectedVal = Double.parseDouble(input.expectedLabel());
			}
			catch (NumberFormatException e) {
				return new JudgeScorerResult(false, 0.0, "Expected numeric label, got: " + input.expectedLabel());
			}
			double actualVal = input.actual().score() instanceof NumericalScore ns ? ns.normalized()
					: (input.actual().pass() ? 1.0 : 0.0);
			double delta = Math.abs(actualVal - expectedVal);
			boolean match = delta <= tolerance;
			return new JudgeScorerResult(match, Math.max(0.0, 1.0 - delta),
					"Expected " + expectedVal + " ± " + tolerance + ", got " + actualVal);
		};
	}

}
