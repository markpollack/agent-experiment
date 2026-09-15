package io.github.markpollack.experiment.scoring;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import io.github.markpollack.judge.jury.Decision;
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

		Verdict verdict = Verdict.of(Judgment.pass("All passed"), byName);

		Map<String, Double> scores = VerdictExtractor.extractScores(verdict);
		assertThat(scores).hasSize(2).containsEntry("build_success", 1.0).containsEntry("file_comparison", 0.85);
	}

	@Test
	void passedReturnsTrueForPassingVerdict() {
		Verdict verdict = Verdict.builder().aggregated(Judgment.pass("All good")).decision(Decision.own()).build();

		assertThat(VerdictExtractor.passed(verdict)).isTrue();
	}

	@Test
	void passedReturnsFalseForFailingVerdict() {
		Verdict verdict = Verdict.builder().aggregated(Judgment.fail("Build failed")).decision(Decision.own()).build();

		assertThat(VerdictExtractor.passed(verdict)).isFalse();
	}

	@Test
	void handlesEmptyIndividualByName() {
		Verdict verdict = Verdict.builder()
			.aggregated(Judgment.pass("No individual judges"))
			.decision(Decision.own())
			.build();

		assertThat(VerdictExtractor.extractScores(verdict)).isEmpty();
	}

	@Test
	void projectsLabeledPassOutcomeThroughEffectiveScore() {
		Map<String, Judgment> byName = new LinkedHashMap<>();
		byName.put("build", Judgment.pass("ok"));
		byName.put("category", Judgment.builder().pass().label("good").reasoning("classified").build());

		Verdict verdict = Verdict.of(Judgment.pass("Mixed"), byName);

		Map<String, Double> scores = VerdictExtractor.extractScores(verdict);
		assertThat(scores).hasSize(2).containsEntry("build", 1.0).containsEntry("category", 1.0);
	}

	@Test
	void omitsAbstainAndErrorWithoutManufacturingZeroes() {
		Map<String, Judgment> byName = new LinkedHashMap<>();
		byName.put("not_applicable", Judgment.abstain("not applicable"));
		byName.put("unavailable", Judgment.error("unavailable"));

		Verdict verdict = Verdict.of(Judgment.abstain("no eligible judges"), byName);

		assertThat(VerdictExtractor.extractScores(verdict)).isEmpty();
	}

	private static Verdict verdictWithSingleJudge(String name, Judgment judgment) {
		return Verdict.of(judgment, Map.of(name, judgment));
	}

}
