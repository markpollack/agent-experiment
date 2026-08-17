package io.github.markpollack.experiment.judge;

import java.nio.file.Path;
import java.util.List;

import io.github.markpollack.experiment.dataset.DatasetItem;
import io.github.markpollack.judge.result.Judgment;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JudgeScorersTest {

	private static final DatasetItem DUMMY_ITEM = new DatasetItem("test-1", "test-slug", "test task", "test", "A",
			false, List.of(), List.of(), "active", Path.of("/tmp/test"), null, null);

	// --- exactVerdictMatch ---

	@Test
	void exactVerdictMatch_passMatchesPass() {
		Judgment judgment = Judgment.pass("ok");

		JudgeScorerResult result = JudgeScorers.exactVerdictMatch()
			.score(new JudgeScoringInput(DUMMY_ITEM, judgment, "PASS"));

		assertThat(result.match()).isTrue();
		assertThat(result.score()).isEqualTo(1.0);
	}

	@Test
	void exactVerdictMatch_passMismatchesFail() {
		Judgment judgment = Judgment.pass("ok");

		JudgeScorerResult result = JudgeScorers.exactVerdictMatch()
			.score(new JudgeScoringInput(DUMMY_ITEM, judgment, "FAIL"));

		assertThat(result.match()).isFalse();
		assertThat(result.score()).isEqualTo(0.0);
	}

	@Test
	void exactVerdictMatch_caseInsensitive() {
		Judgment judgment = Judgment.fail("bad");

		JudgeScorerResult result = JudgeScorers.exactVerdictMatch()
			.score(new JudgeScoringInput(DUMMY_ITEM, judgment, "fail"));

		assertThat(result.match()).isTrue();
	}

	// --- exactCategoryMatch ---

	@Test
	void exactCategoryMatch_matchingCategory() {
		Judgment judgment = Judgment.builder().pass().label("HIGH").reasoning("high quality").build();

		JudgeScorerResult result = JudgeScorers.exactCategoryMatch()
			.score(new JudgeScoringInput(DUMMY_ITEM, judgment, "HIGH"));

		assertThat(result.match()).isTrue();
		assertThat(result.score()).isEqualTo(1.0);
	}

	@Test
	void exactCategoryMatch_mismatch() {
		Judgment judgment = Judgment.builder().pass().label("LOW").reasoning("low quality").build();

		JudgeScorerResult result = JudgeScorers.exactCategoryMatch()
			.score(new JudgeScoringInput(DUMMY_ITEM, judgment, "HIGH"));

		assertThat(result.match()).isFalse();
		assertThat(result.score()).isEqualTo(0.0);
	}

	@Test
	void exactCategoryMatch_fallsBackToStatusForNonCategorical() {
		Judgment judgment = Judgment.pass("ok");

		JudgeScorerResult result = JudgeScorers.exactCategoryMatch()
			.score(new JudgeScoringInput(DUMMY_ITEM, judgment, "PASS"));

		assertThat(result.match()).isTrue();
	}

	// --- numericalTolerance ---

	@Test
	void numericalTolerance_withinTolerance() {
		Judgment judgment = Judgment.builder().pass().score(0.85).reasoning("good").build();

		JudgeScorerResult result = JudgeScorers.numericalTolerance(0.1)
			.score(new JudgeScoringInput(DUMMY_ITEM, judgment, "0.8"));

		assertThat(result.match()).isTrue();
		assertThat(result.score()).isGreaterThan(0.9);
	}

	@Test
	void numericalTolerance_outsideTolerance() {
		Judgment judgment = Judgment.builder().fail().score(0.3).reasoning("bad").build();

		JudgeScorerResult result = JudgeScorers.numericalTolerance(0.1)
			.score(new JudgeScoringInput(DUMMY_ITEM, judgment, "0.8"));

		assertThat(result.match()).isFalse();
		assertThat(result.score()).isLessThan(0.6);
	}

	@Test
	void numericalTolerance_nonNumericLabel() {
		Judgment judgment = Judgment.builder().pass().score(0.5).reasoning("ok").build();

		JudgeScorerResult result = JudgeScorers.numericalTolerance(0.1)
			.score(new JudgeScoringInput(DUMMY_ITEM, judgment, "not-a-number"));

		assertThat(result.match()).isFalse();
		assertThat(result.score()).isEqualTo(0.0);
		assertThat(result.reasoning()).contains("Expected numeric label");
	}

	@Test
	void numericalTolerance_fallsBackToPassFlagForNonNumerical() {
		Judgment judgment = Judgment.pass("ok");

		JudgeScorerResult result = JudgeScorers.numericalTolerance(0.1)
			.score(new JudgeScoringInput(DUMMY_ITEM, judgment, "1.0"));

		assertThat(result.match()).isTrue();
	}

}
