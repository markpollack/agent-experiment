package io.github.markpollack.experiment.judge;

import java.util.List;

import java.nio.file.Path;
import java.util.List;

import io.github.markpollack.experiment.dataset.DatasetItem;
import io.github.markpollack.judge.result.Judgment;
import io.github.markpollack.judge.result.JudgmentStatus;
import io.github.markpollack.judge.score.BooleanScore;
import io.github.markpollack.judge.score.CategoricalScore;
import io.github.markpollack.judge.score.NumericalScore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JudgeScorersTest {

	private static final DatasetItem DUMMY_ITEM = new DatasetItem("test-1", "test-slug", "test task", "test", "A",
			false, List.of(), List.of(), "active", Path.of("/tmp/test"), null, null);

	// --- exactVerdictMatch ---

	@Test
	void exactVerdictMatch_passMatchesPass() {
		Judgment judgment = Judgment.builder()
			.score(new BooleanScore(true))
			.status(JudgmentStatus.PASS)
			.reasoning("ok")
			.build();

		JudgeScorerResult result = JudgeScorers.exactVerdictMatch()
			.score(new JudgeScoringInput(DUMMY_ITEM, judgment, "PASS"));

		assertThat(result.match()).isTrue();
		assertThat(result.score()).isEqualTo(1.0);
	}

	@Test
	void exactVerdictMatch_passMismatchesFail() {
		Judgment judgment = Judgment.builder()
			.score(new BooleanScore(true))
			.status(JudgmentStatus.PASS)
			.reasoning("ok")
			.build();

		JudgeScorerResult result = JudgeScorers.exactVerdictMatch()
			.score(new JudgeScoringInput(DUMMY_ITEM, judgment, "FAIL"));

		assertThat(result.match()).isFalse();
		assertThat(result.score()).isEqualTo(0.0);
	}

	@Test
	void exactVerdictMatch_caseInsensitive() {
		Judgment judgment = Judgment.builder()
			.score(new BooleanScore(false))
			.status(JudgmentStatus.FAIL)
			.reasoning("bad")
			.build();

		JudgeScorerResult result = JudgeScorers.exactVerdictMatch()
			.score(new JudgeScoringInput(DUMMY_ITEM, judgment, "fail"));

		assertThat(result.match()).isTrue();
	}

	// --- exactCategoryMatch ---

	@Test
	void exactCategoryMatch_matchingCategory() {
		Judgment judgment = Judgment.builder()
			.score(new CategoricalScore("HIGH", List.of("LOW", "MEDIUM", "HIGH")))
			.status(JudgmentStatus.PASS)
			.reasoning("high quality")
			.build();

		JudgeScorerResult result = JudgeScorers.exactCategoryMatch()
			.score(new JudgeScoringInput(DUMMY_ITEM, judgment, "HIGH"));

		assertThat(result.match()).isTrue();
		assertThat(result.score()).isEqualTo(1.0);
	}

	@Test
	void exactCategoryMatch_mismatch() {
		Judgment judgment = Judgment.builder()
			.score(new CategoricalScore("LOW", List.of("LOW", "MEDIUM", "HIGH")))
			.status(JudgmentStatus.PASS)
			.reasoning("low quality")
			.build();

		JudgeScorerResult result = JudgeScorers.exactCategoryMatch()
			.score(new JudgeScoringInput(DUMMY_ITEM, judgment, "HIGH"));

		assertThat(result.match()).isFalse();
		assertThat(result.score()).isEqualTo(0.0);
	}

	@Test
	void exactCategoryMatch_fallsBackToStatusForNonCategorical() {
		Judgment judgment = Judgment.builder()
			.score(new BooleanScore(true))
			.status(JudgmentStatus.PASS)
			.reasoning("ok")
			.build();

		JudgeScorerResult result = JudgeScorers.exactCategoryMatch()
			.score(new JudgeScoringInput(DUMMY_ITEM, judgment, "PASS"));

		assertThat(result.match()).isTrue();
	}

	// --- numericalTolerance ---

	@Test
	void numericalTolerance_withinTolerance() {
		Judgment judgment = Judgment.builder()
			.score(new NumericalScore(0.85, 0.0, 1.0))
			.status(JudgmentStatus.PASS)
			.reasoning("good")
			.build();

		JudgeScorerResult result = JudgeScorers.numericalTolerance(0.1)
			.score(new JudgeScoringInput(DUMMY_ITEM, judgment, "0.8"));

		assertThat(result.match()).isTrue();
		assertThat(result.score()).isGreaterThan(0.9);
	}

	@Test
	void numericalTolerance_outsideTolerance() {
		Judgment judgment = Judgment.builder()
			.score(new NumericalScore(0.3, 0.0, 1.0))
			.status(JudgmentStatus.FAIL)
			.reasoning("bad")
			.build();

		JudgeScorerResult result = JudgeScorers.numericalTolerance(0.1)
			.score(new JudgeScoringInput(DUMMY_ITEM, judgment, "0.8"));

		assertThat(result.match()).isFalse();
		assertThat(result.score()).isLessThan(0.6);
	}

	@Test
	void numericalTolerance_nonNumericLabel() {
		Judgment judgment = Judgment.builder()
			.score(new NumericalScore(0.5, 0.0, 1.0))
			.status(JudgmentStatus.PASS)
			.reasoning("ok")
			.build();

		JudgeScorerResult result = JudgeScorers.numericalTolerance(0.1)
			.score(new JudgeScoringInput(DUMMY_ITEM, judgment, "not-a-number"));

		assertThat(result.match()).isFalse();
		assertThat(result.score()).isEqualTo(0.0);
		assertThat(result.reasoning()).contains("Expected numeric label");
	}

	@Test
	void numericalTolerance_fallsBackToPassFlagForNonNumerical() {
		Judgment judgment = Judgment.builder()
			.score(new BooleanScore(true))
			.status(JudgmentStatus.PASS)
			.reasoning("ok")
			.build();

		JudgeScorerResult result = JudgeScorers.numericalTolerance(0.1)
			.score(new JudgeScoringInput(DUMMY_ITEM, judgment, "1.0"));

		assertThat(result.match()).isTrue();
	}

}
