package io.github.markpollack.experiment.scoring;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import io.github.markpollack.judge.jury.Verdict;
import io.github.markpollack.judge.result.Judgment;

import static org.assertj.core.api.Assertions.assertThat;

class VerdictExtractorTest {

	@Test
	void extractsBooleanOutcomeAsOneOrZero() {
		Verdict passing = verdictWithSingleJudge("build", Judgment.pass("ok"));
		Verdict failing = verdictWithSingleJudge("build", Judgment.fail("bad"));

		assertThat(VerdictExtractor.extractScores(passing)).containsEntry("build", 1.0);
		assertThat(VerdictExtractor.extractScores(failing)).containsEntry("build", 0.0);
	}

	@Test
	void extractsAlreadyNormalizedMeasuredScore() {
		Judgment judgment = Judgment.builder().pass().score(0.7).reasoning("test").build();
		Verdict verdict = verdictWithSingleJudge("quality", judgment);

		assertThat(VerdictExtractor.extractScores(verdict)).containsEntry("quality", 0.7);
	}

	@Test
	void extractsMultipleJudgeScores() {
		Map<String, Judgment> byName = new LinkedHashMap<>();
		byName.put("build_success", Judgment.pass("ok"));
		byName.put("file_comparison", Judgment.builder().pass().score(0.85).reasoning("test").build());

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
	void projectsLabeledPassOutcomeThroughEffectiveScore() {
		Map<String, Judgment> byName = new LinkedHashMap<>();
		byName.put("build", Judgment.pass("ok"));
		byName.put("category", Judgment.builder().pass().label("good").reasoning("classified").build());

		Verdict verdict = Verdict.builder().aggregated(Judgment.pass("Mixed")).individualByName(byName).build();

		Map<String, Double> scores = VerdictExtractor.extractScores(verdict);
		assertThat(scores).hasSize(2).containsEntry("build", 1.0).containsEntry("category", 1.0);
	}

	@Test
	void omitsAbstainAndErrorWithoutManufacturingZeroes() {
		Map<String, Judgment> byName = new LinkedHashMap<>();
		byName.put("not_applicable", Judgment.abstain("not applicable"));
		byName.put("unavailable", Judgment.error("unavailable"));

		Verdict verdict = Verdict.builder()
			.aggregated(Judgment.abstain("no eligible judges"))
			.individualByName(byName)
			.build();

		assertThat(VerdictExtractor.extractScores(verdict)).isEmpty();
	}

	private static Verdict verdictWithSingleJudge(String name, Judgment judgment) {
		return Verdict.builder().aggregated(judgment).individualByName(Map.of(name, judgment)).build();
	}

}
