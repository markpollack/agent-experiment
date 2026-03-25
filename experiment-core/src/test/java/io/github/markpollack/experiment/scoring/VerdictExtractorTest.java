package io.github.markpollack.experiment.scoring;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springaicommunity.judge.jury.Verdict;
import org.springaicommunity.judge.result.Judgment;
import org.springaicommunity.judge.result.JudgmentStatus;
import org.springaicommunity.judge.score.BooleanScore;
import org.springaicommunity.judge.score.CategoricalScore;
import org.springaicommunity.judge.score.NumericalScore;

import static org.assertj.core.api.Assertions.assertThat;

class VerdictExtractorTest {

	@Test
	void extractsBooleanScoreAsOneOrZero() {
		Verdict passing = verdictWithSingleJudge("build", judgmentWith(new BooleanScore(true)));
		Verdict failing = verdictWithSingleJudge("build", judgmentWith(new BooleanScore(false)));

		assertThat(VerdictExtractor.extractScores(passing)).containsEntry("build", 1.0);
		assertThat(VerdictExtractor.extractScores(failing)).containsEntry("build", 0.0);
	}

	@Test
	void extractsNumericalScoreViaNormalized() {
		NumericalScore score = new NumericalScore(7.0, 0.0, 10.0);
		Verdict verdict = verdictWithSingleJudge("quality", judgmentWith(score));

		assertThat(VerdictExtractor.extractScores(verdict)).containsEntry("quality", 0.7);
	}

	@Test
	void extractsMultipleJudgeScores() {
		Map<String, Judgment> byName = new LinkedHashMap<>();
		byName.put("build_success", judgmentWith(new BooleanScore(true)));
		byName.put("file_comparison", judgmentWith(NumericalScore.normalized(0.85)));

		Verdict verdict = Verdict.builder().aggregated(Judgment.pass("All passed")).individualByName(byName).build();

		Map<String, Double> scores = VerdictExtractor.extractScores(verdict);
		assertThat(scores).hasSize(2).containsEntry("build_success", 1.0).containsEntry("file_comparison", 0.85);
	}

	@Test
	void passedReturnsTrueForPassingVerdict() {
		Verdict verdict = Verdict.builder().aggregated(Judgment.pass("All good")).build();

		assertThat(VerdictExtractor.passed(verdict)).isTrue();
	}

	@Test
	void passedReturnsFalseForFailingVerdict() {
		Verdict verdict = Verdict.builder().aggregated(Judgment.fail("Build failed")).build();

		assertThat(VerdictExtractor.passed(verdict)).isFalse();
	}

	@Test
	void handlesEmptyIndividualByName() {
		Verdict verdict = Verdict.builder().aggregated(Judgment.pass("No individual judges")).build();

		assertThat(VerdictExtractor.extractScores(verdict)).isEmpty();
	}

	@Test
	void skipsCategoricalScores() {
		Map<String, Judgment> byName = new LinkedHashMap<>();
		byName.put("build", judgmentWith(new BooleanScore(true)));
		byName.put("category", judgmentWith(new CategoricalScore("good", List.of("good", "fair", "poor"))));

		Verdict verdict = Verdict.builder().aggregated(Judgment.pass("Mixed")).individualByName(byName).build();

		Map<String, Double> scores = VerdictExtractor.extractScores(verdict);
		assertThat(scores).hasSize(1).containsEntry("build", 1.0);
	}

	private static Verdict verdictWithSingleJudge(String name, Judgment judgment) {
		return Verdict.builder().aggregated(judgment).individualByName(Map.of(name, judgment)).build();
	}

	private static Judgment judgmentWith(org.springaicommunity.judge.score.Score score) {
		boolean pass = score instanceof BooleanScore bs ? bs.value() : true;
		return Judgment.builder()
			.score(score)
			.status(pass ? JudgmentStatus.PASS : JudgmentStatus.FAIL)
			.reasoning("test")
			.build();
	}

}
