package io.github.markpollack.experiment.diagnostic;

import java.util.List;
import java.util.Map;

import io.github.markpollack.experiment.pipeline.ExecutionPlan;
import org.junit.jupiter.api.Test;
import org.springaicommunity.judge.jury.Verdict;
import org.springaicommunity.judge.result.Check;
import org.springaicommunity.judge.result.Judgment;
import org.springaicommunity.judge.result.JudgmentStatus;
import org.springaicommunity.judge.score.BooleanScore;

import static org.assertj.core.api.Assertions.assertThat;

class HeuristicGapClassifierTest {

	private final HeuristicGapClassifier classifier = new HeuristicGapClassifier();

	// --- GapCategory enum ---

	@Test
	void gapCategoryHasDescriptionAndAdvice() {
		for (GapCategory cat : GapCategory.values()) {
			assertThat(cat.description()).isNotBlank();
			assertThat(cat.actionableAdvice()).isNotBlank();
		}
	}

	@Test
	void gapCategoryHasEightValues() {
		assertThat(GapCategory.values()).hasSize(8);
	}

	// --- BuildSuccessJudge / CommandJudge ---

	@Test
	void buildFailureClassifiedAsAgentExecutionGap() {
		Verdict verdict = verdictWithJudge("CommandJudge",
				Judgment.builder()
					.status(JudgmentStatus.FAIL)
					.score(new BooleanScore(false))
					.reasoning("Build failed")
					.check(Check.fail("command_execution", "Command failed"))
					.build());

		List<DiagnosticCheck> checks = classifier.classify(verdict, null, null);

		assertThat(checks).hasSize(1);
		assertThat(checks.get(0).gapCategory()).isEqualTo(GapCategory.AGENT_EXECUTION_GAP);
		assertThat(checks.get(0).judgeName()).isEqualTo("CommandJudge");
	}

	// --- JavaxMigrationJudge ---

	@Test
	void javaxMigrationFailWithPlanCoverageIsAgentGap() {
		ExecutionPlan plan = planWithRoadmap("## Step 1\n- [ ] RUN javax-to-jakarta --apply src/main");

		Verdict verdict = verdictWithJudge("JavaxMigrationJudge",
				Judgment.builder()
					.status(JudgmentStatus.FAIL)
					.score(new BooleanScore(false))
					.reasoning("javax.persistence remaining")
					.check(Check.fail("javax.persistence removed", "38 residual imports"))
					.build());

		List<DiagnosticCheck> checks = classifier.classify(verdict, null, plan);

		assertThat(checks).hasSize(1);
		assertThat(checks.get(0).gapCategory()).isEqualTo(GapCategory.AGENT_EXECUTION_GAP);
		assertThat(checks.get(0).rationale()).contains("Plan covered");
	}

	@Test
	void javaxMigrationFailWithoutPlanCoverageIsPlanGap() {
		ExecutionPlan plan = planWithRoadmap("## Step 1\n- [ ] Manual code changes");

		Verdict verdict = verdictWithJudge("JavaxMigrationJudge",
				Judgment.builder()
					.status(JudgmentStatus.FAIL)
					.score(new BooleanScore(false))
					.reasoning("javax.xml.bind remaining")
					.check(Check.fail("javax.xml.bind removed", "2 residual imports"))
					.build());

		List<DiagnosticCheck> checks = classifier.classify(verdict, null, plan);

		assertThat(checks).hasSize(1);
		assertThat(checks.get(0).gapCategory()).isEqualTo(GapCategory.PLAN_GENERATION_GAP);
		assertThat(checks.get(0).rationale()).contains("Plan didn't cover");
	}

	// --- TestInvarianceJudge ---

	@Test
	void testInvarianceAbstainIsAnalysisGap() {
		Verdict verdict = verdictWithJudge("TestInvarianceJudge",
				Judgment.abstain("No target/surefire-reports directory found"));

		List<DiagnosticCheck> checks = classifier.classify(verdict, null, null);

		assertThat(checks).hasSize(1);
		assertThat(checks.get(0).gapCategory()).isEqualTo(GapCategory.ANALYSIS_GAP);
	}

	@Test
	void testInvarianceFailIsAgentExecutionGap() {
		Verdict verdict = verdictWithJudge("TestInvarianceJudge",
				Judgment.builder()
					.status(JudgmentStatus.FAIL)
					.score(new BooleanScore(false))
					.reasoning("Test count decreased")
					.check(Check.fail("count_non_decreasing", "40 < 41"))
					.build());

		List<DiagnosticCheck> checks = classifier.classify(verdict, null, null);

		assertThat(checks).hasSize(1);
		assertThat(checks.get(0).gapCategory()).isEqualTo(GapCategory.AGENT_EXECUTION_GAP);
		assertThat(checks.get(0).rationale()).contains("broke existing tests");
	}

	// --- DependencyVersionJudge ---

	@Test
	void dependencyVersionFailWithPlanCoverageIsAgentGap() {
		ExecutionPlan plan = planWithRoadmap("## Step 1\n- [ ] RUN pom-upgrader --target 3.0.0");

		Verdict verdict = verdictWithJudge("DependencyVersionJudge",
				Judgment.builder()
					.status(JudgmentStatus.FAIL)
					.score(new BooleanScore(false))
					.reasoning("Version mismatch")
					.check(Check.fail("boot_version_valid", "Boot 2.7.3 != 3.0.0"))
					.build());

		List<DiagnosticCheck> checks = classifier.classify(verdict, null, plan);

		assertThat(checks).hasSize(1);
		assertThat(checks.get(0).gapCategory()).isEqualTo(GapCategory.AGENT_EXECUTION_GAP);
	}

	@Test
	void dependencyVersionFailWithoutPlanIsPlanGap() {
		ExecutionPlan plan = planWithRoadmap("## Step 1\n- [ ] Only manual edits");

		Verdict verdict = verdictWithJudge("DependencyVersionJudge",
				Judgment.builder()
					.status(JudgmentStatus.FAIL)
					.score(new BooleanScore(false))
					.reasoning("Version mismatch")
					.check(Check.fail("boot_version_valid", "Boot 2.7.3 != 3.0.0"))
					.build());

		List<DiagnosticCheck> checks = classifier.classify(verdict, null, plan);

		assertThat(checks).hasSize(1);
		assertThat(checks.get(0).gapCategory()).isEqualTo(GapCategory.PLAN_GENERATION_GAP);
	}

	// --- ASTDiffJudge ---

	@Test
	void astDiffAbstainIsAnalysisGap() {
		Verdict verdict = verdictWithJudge("ASTDiffJudge", Judgment.abstain("No beforeDir in metadata"));

		List<DiagnosticCheck> checks = classifier.classify(verdict, null, null);

		assertThat(checks).hasSize(1);
		assertThat(checks.get(0).gapCategory()).isEqualTo(GapCategory.ANALYSIS_GAP);
	}

	// --- SemanticDiffJudge ---

	@Test
	void semanticDiffFailOnBuildCriterionIsCriteriaGap() {
		Verdict verdict = verdictWithJudge("SemanticDiffJudge",
				Judgment.builder()
					.status(JudgmentStatus.FAIL)
					.score(new BooleanScore(false))
					.reasoning("1/2 criteria passed")
					.check(Check.fail("./mvnw clean compile", "Build criterion"))
					.check(Check.pass("No javax.persistence imports", "Verified"))
					.build());

		List<DiagnosticCheck> checks = classifier.classify(verdict, null, null);

		assertThat(checks).hasSize(1);
		assertThat(checks.get(0).gapCategory()).isEqualTo(GapCategory.CRITERIA_GAP);
		assertThat(checks.get(0).rationale()).contains("overlaps with deterministic judges");
	}

	@Test
	void semanticDiffFailOnMigrationCriterionIsAgentGap() {
		Verdict verdict = verdictWithJudge("SemanticDiffJudge",
				Judgment.builder()
					.status(JudgmentStatus.FAIL)
					.score(new BooleanScore(false))
					.reasoning("0/1 criteria passed")
					.check(Check.fail("All JPA entities use jakarta.persistence", "Still using javax"))
					.build());

		List<DiagnosticCheck> checks = classifier.classify(verdict, null, null);

		assertThat(checks).hasSize(1);
		assertThat(checks.get(0).gapCategory()).isEqualTo(GapCategory.AGENT_EXECUTION_GAP);
	}

	// --- Structural judges ---

	@Test
	void importDiffFailIsAgentGap() {
		Verdict verdict = verdictWithJudge("ImportDiffJudge",
				Judgment.builder()
					.status(JudgmentStatus.FAIL)
					.score(new BooleanScore(false))
					.reasoning("Imports incomplete")
					.check(Check.fail("import_migration", "80% complete"))
					.build());

		List<DiagnosticCheck> checks = classifier.classify(verdict, null, null);

		assertThat(checks).hasSize(1);
		assertThat(checks.get(0).gapCategory()).isEqualTo(GapCategory.AGENT_EXECUTION_GAP);
	}

	// --- Unknown judges ---

	@Test
	void unknownJudgeGetsNullCategory() {
		Verdict verdict = verdictWithJudge("CustomJudge",
				Judgment.builder()
					.status(JudgmentStatus.FAIL)
					.score(new BooleanScore(false))
					.reasoning("Custom failure")
					.check(Check.fail("custom_check", "Something failed"))
					.build());

		List<DiagnosticCheck> checks = classifier.classify(verdict, null, null);

		assertThat(checks).hasSize(1);
		assertThat(checks.get(0).gapCategory()).isNull();
		assertThat(checks.get(0).rationale()).contains("Unknown judge");
	}

	// --- Passing verdicts ---

	@Test
	void passingVerdictProducesNoDiagnostics() {
		Verdict verdict = verdictWithJudge("CommandJudge",
				Judgment.builder()
					.status(JudgmentStatus.PASS)
					.score(new BooleanScore(true))
					.reasoning("Build succeeded")
					.check(Check.pass("command_execution", "OK"))
					.build());

		List<DiagnosticCheck> checks = classifier.classify(verdict, null, null);

		assertThat(checks).isEmpty();
	}

	// --- Multi-check verdict ---

	@Test
	void multipleFailedChecksProduceMultipleDiagnostics() {
		Verdict verdict = verdictWithJudge("JavaxMigrationJudge",
				Judgment.builder()
					.status(JudgmentStatus.FAIL)
					.score(new BooleanScore(false))
					.reasoning("2 namespaces remaining")
					.check(Check.fail("javax.persistence removed", "38 imports"))
					.check(Check.fail("javax.validation removed", "9 imports"))
					.check(Check.pass("javax.servlet removed", "clean"))
					.build());

		ExecutionPlan plan = planWithRoadmap("RUN javax-to-jakarta");

		List<DiagnosticCheck> checks = classifier.classify(verdict, null, plan);

		assertThat(checks).hasSize(2);
		assertThat(checks).allSatisfy(dc -> assertThat(dc.gapCategory()).isEqualTo(GapCategory.AGENT_EXECUTION_GAP));
	}

	// --- CascadedJury sub-verdicts ---

	@Test
	void classifiesSubVerdicts() {
		Verdict tier1 = verdictWithJudge("CommandJudge",
				Judgment.builder()
					.status(JudgmentStatus.FAIL)
					.score(new BooleanScore(false))
					.reasoning("Build failed")
					.check(Check.fail("command_execution", "exit 1"))
					.build());

		Verdict cascaded = Verdict.builder()
			.aggregated(tier1.aggregated())
			.individual(tier1.individual())
			.individualByName(tier1.individualByName())
			.subVerdicts(List.of(tier1))
			.build();

		List<DiagnosticCheck> checks = classifier.classify(cascaded, null, null);

		// One from main verdict, one from sub-verdict (same check appears twice)
		assertThat(checks).hasSize(2);
		assertThat(checks).allSatisfy(dc -> assertThat(dc.gapCategory()).isEqualTo(GapCategory.AGENT_EXECUTION_GAP));
	}

	// --- helpers ---

	private static Verdict verdictWithJudge(String judgeName, Judgment judgment) {
		return Verdict.builder()
			.aggregated(judgment)
			.individual(List.of(judgment))
			.individualByName(Map.of(judgeName, judgment))
			.build();
	}

	private static ExecutionPlan planWithRoadmap(String roadmap) {
		return new ExecutionPlan(roadmap, List.of(), List.of(), 0.0, 0, 0, 0, 0, null);
	}

}
