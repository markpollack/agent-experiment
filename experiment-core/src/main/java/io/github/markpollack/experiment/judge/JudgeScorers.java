package io.github.markpollack.experiment.judge;

import java.util.OptionalDouble;

import io.github.markpollack.judge.result.Judgment;

/**
 * Built-in {@link JudgeScorer} implementations.
 *
 * <p>
 * A scorer compares what the candidate judge said with what the dataset expected. When
 * the candidate said nothing comparable — it errored, abstained, or found the criterion
 * inapplicable — the scorer returns {@link JudgeScorerResult#unscored}, because inventing
 * a number there does not produce a wrong score so much as a confident one.
 */
public final class JudgeScorers {

	private JudgeScorers() {
	}

	/** Judge PASS/FAIL must exactly match expected "PASS"/"FAIL" label. */
	public static JudgeScorer exactVerdictMatch() {
		return identified("status-agreement:v1", "1", "STATUS_LABEL", input -> {
			// A status is always present, and a dataset may legitimately expect ERROR or
			// NOT_APPLICABLE, so this comparison is always meaningful.
			boolean match = input.actual().status().name().equalsIgnoreCase(input.expectedLabel());
			return new JudgeScorerResult(match, match ? 1.0 : 0.0,
					"Expected " + input.expectedLabel() + ", got " + input.actual().status());
		});
	}

	/** Judgment label must match expected label (case-insensitive). */
	public static JudgeScorer exactCategoryMatch() {
		return identified("legacy-category-agreement:v1", "1", "CATEGORY", input -> {
			if (!classified(input.actual())) {
				// Raw labels can survive withholding or policy failure. They do not
				// make an operationally ineligible judgment a category vote.
				return JudgeScorerResult.unscored("Candidate produced no category (" + input.actual().status()
						+ "); nothing to compare with '" + input.expectedLabel() + "'");
			}
			String actualCategory = input.actual().label() != null ? input.actual().label()
					: input.actual().status().name();
			boolean match = actualCategory.equalsIgnoreCase(input.expectedLabel());
			return new JudgeScorerResult(match, match ? 1.0 : 0.0,
					"Expected category '" + input.expectedLabel() + "', got '" + actualCategory + "'");
		});
	}

	/** Normalized measured score must be within tolerance of the expected value. */
	public static JudgeScorer numericalTolerance(double tolerance) {
		return identified("legacy-normalized-tolerance:v1:" + Double.toHexString(tolerance), "1", "NUMERIC", input -> {
			double expectedVal;
			try {
				expectedVal = Double.parseDouble(input.expectedLabel());
			}
			catch (NumberFormatException e) {
				return new JudgeScorerResult(false, 0.0, "Expected numeric label, got: " + input.expectedLabel());
			}
			OptionalDouble measured = input.actual().effectiveScore();
			if (measured.isEmpty()) {
				// The candidate measured nothing. The former fallback — pass ? 1.0 : 0.0
				// —
				// invented a measurement, and against an expected 0 that invention scored
				// as a correct agreement.
				return JudgeScorerResult.unscored("Candidate made no measurement (" + input.actual().status()
						+ "); nothing to compare with " + expectedVal);
			}
			double actualVal = measured.getAsDouble();
			double delta = Math.abs(actualVal - expectedVal);
			boolean match = delta <= tolerance;
			return new JudgeScorerResult(match, Math.max(0.0, 1.0 - delta),
					"Expected " + expectedVal + " ± " + tolerance + ", got " + actualVal);
		});
	}

	/** Native Boolean proposition agreement, distinct from operational acceptance. */
	public static JudgeScorer propositionMatch() {
		return identified("proposition-match:v1", "1", "PROPOSITION", input -> {
			Judgment actual = input.actual();
			if (!classified(actual) || actual.assessment() == null || actual.assessment().proposition() == null
					|| actual.assessment().proposition().value() == null)
				return JudgeScorerResult.unscored("Candidate has no eligible Boolean proposition");
			if (!"true".equals(input.expectedLabel()) && !"false".equals(input.expectedLabel()))
				return JudgeScorerResult.unscored("Missing Boolean truth");
			boolean match = actual.assessment().proposition().value() == Boolean.parseBoolean(input.expectedLabel());
			return new JudgeScorerResult(match, match ? 1.0 : 0.0, "Native proposition equality");
		});
	}

	/** Identify a custom scorer so its results cannot silently join a different rule. */
	public static JudgeScorer identified(String measure, String revision, String truthKind, JudgeScorer delegate) {
		return new Identified(measure, revision, truthKind, delegate);
	}

	private record Identified(String measureId, String scorerRevision, String truthKind,
			JudgeScorer delegate) implements JudgeScorer {
		@Override
		public JudgeScorerResult score(JudgeScoringInput input) {
			return delegate.score(input);
		}
	}

	/** Whether this judgment classified the subject at all. */
	private static boolean classified(Judgment judgment) {
		return switch (judgment.status()) {
			case PASS, FAIL -> true;
			case ABSTAIN, NOT_APPLICABLE, ERROR -> false;
		};
	}

}
