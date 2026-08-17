package io.github.markpollack.experiment.diagnostic;

import java.util.ArrayList;
import java.util.List;

import io.github.markpollack.experiment.pipeline.AnalysisEnvelope;
import io.github.markpollack.experiment.pipeline.ExecutionPlan;
import io.github.markpollack.experiment.result.RecordedCheck;
import io.github.markpollack.experiment.result.RecordedJudgment;
import io.github.markpollack.experiment.result.RecordedJudgmentStatus;
import io.github.markpollack.experiment.result.RecordedVerdict;
import org.jspecify.annotations.Nullable;

/**
 * Heuristic-based gap classifier that maps judge name + failure type to gap categories.
 *
 * <p>
 * Classification rules:
 * <ul>
 * <li>BuildSuccessJudge/CommandJudge FAIL → AGENT_EXECUTION_GAP</li>
 * <li>JavaxMigrationJudge FAIL + plan covered it → AGENT_EXECUTION_GAP</li>
 * <li>JavaxMigrationJudge FAIL + plan didn't cover → PLAN_GENERATION_GAP</li>
 * <li>TestInvarianceJudge FAIL → AGENT_EXECUTION_GAP</li>
 * <li>TestInvarianceJudge ABSTAIN → ANALYSIS_GAP</li>
 * <li>DependencyVersionJudge FAIL → AGENT_EXECUTION_GAP</li>
 * <li>ASTDiffJudge ABSTAIN → ANALYSIS_GAP</li>
 * <li>SemanticDiffJudge FAIL → CRITERIA_GAP or AGENT_EXECUTION_GAP</li>
 * <li>Unknown judges → null category</li>
 * </ul>
 */
public class HeuristicGapClassifier implements GapClassifier {

	@Override
	public List<DiagnosticCheck> classify(RecordedVerdict verdict, @Nullable AnalysisEnvelope analysis,
			@Nullable ExecutionPlan plan) {
		List<DiagnosticCheck> results = new ArrayList<>();
		classifyVerdict(verdict, plan, results);
		return List.copyOf(results);
	}

	private void classifyVerdict(RecordedVerdict verdict, @Nullable ExecutionPlan plan, List<DiagnosticCheck> results) {
		// Classify individual judgments from the main verdict
		verdict.individualByName().forEach((judgeName, judgment) -> {
			if (judgment.status() == RecordedJudgmentStatus.PASS) {
				return;
			}
			classifyJudgment(judgeName, judgment, plan, results);
		});

		// Recursively classify sub-verdicts (from CascadedJury tiers)
		for (var attempt : verdict.compositeAttempts()) {
			if (attempt.verdict() != null) {
				classifyVerdict(attempt.verdict(), plan, results);
			}
		}
	}

	private void classifyJudgment(String judgeName, RecordedJudgment judgment, @Nullable ExecutionPlan plan,
			List<DiagnosticCheck> results) {
		if (judgment.checks().isEmpty()) {
			// No checks — classify the judgment as a whole
			DiagnosticCheck dc = classifyByJudgeName(judgeName, judgment.status(), null, plan);
			if (dc != null) {
				results.add(dc);
			}
			return;
		}

		for (RecordedCheck check : judgment.checks()) {
			if (check.passed()) {
				continue;
			}
			DiagnosticCheck dc = classifyByJudgeName(judgeName, judgment.status(), check, plan);
			if (dc != null) {
				results.add(dc);
			}
		}
	}

	@Nullable
	private DiagnosticCheck classifyByJudgeName(String judgeName, RecordedJudgmentStatus status,
			@Nullable RecordedCheck check, @Nullable ExecutionPlan plan) {
		return switch (judgeName) {
			case "CommandJudge" -> classifyBuildFailure(judgeName, check);
			case "JavaxMigrationJudge" -> classifyJavaxMigration(judgeName, check, plan);
			case "TestInvarianceJudge" -> classifyTestInvariance(judgeName, status, check);
			case "DependencyVersionJudge" -> classifyDependencyVersion(judgeName, check, plan);
			case "ImportDiffJudge" ->
				classifyStructural(judgeName, check, GapCategory.AGENT_EXECUTION_GAP, "Import migration incomplete");
			case "AnnotationDiffJudge" -> classifyStructural(judgeName, check, GapCategory.AGENT_EXECUTION_GAP,
					"Annotation migration incomplete");
			case "MavenPomDiffJudge" ->
				classifyStructural(judgeName, check, GapCategory.AGENT_EXECUTION_GAP, "POM changes incomplete");
			case "ASTDiffJudge" -> classifyAstDiff(judgeName, status, check);
			case "SemanticDiffJudge" -> classifySemanticDiff(judgeName, check);
			default -> {
				if (check != null) {
					yield new DiagnosticCheck(judgeName, check, null, "Unknown judge — cannot classify");
				}
				yield null;
			}
		};
	}

	private DiagnosticCheck classifyBuildFailure(String judgeName, @Nullable RecordedCheck check) {
		RecordedCheck c = check != null ? check : RecordedCheck.fail("build_failure", "Build failed");
		return new DiagnosticCheck(judgeName, c, GapCategory.AGENT_EXECUTION_GAP,
				"Build failure indicates agent didn't produce compilable code");
	}

	private DiagnosticCheck classifyJavaxMigration(String judgeName, @Nullable RecordedCheck check,
			@Nullable ExecutionPlan plan) {
		if (check == null) {
			return new DiagnosticCheck(judgeName, RecordedCheck.fail("javax_migration", "javax migration incomplete"),
					GapCategory.AGENT_EXECUTION_GAP, "javax imports remaining");
		}

		String checkName = check.name();
		boolean planCovered = plan != null && planCoversNamespace(plan.roadmapMarkdown(), checkName);

		if (planCovered) {
			return new DiagnosticCheck(judgeName, check, GapCategory.AGENT_EXECUTION_GAP,
					"Plan covered " + checkName + " but agent didn't execute it");
		}
		else {
			return new DiagnosticCheck(judgeName, check, GapCategory.PLAN_GENERATION_GAP,
					"Plan didn't cover " + checkName + " — planner missed this namespace");
		}
	}

	private DiagnosticCheck classifyTestInvariance(String judgeName, RecordedJudgmentStatus status,
			@Nullable RecordedCheck check) {
		if (status == RecordedJudgmentStatus.ABSTAIN) {
			RecordedCheck c = check != null ? check
					: RecordedCheck.fail("test_invariance", "Surefire reports not found");
			return new DiagnosticCheck(judgeName, c, GapCategory.ANALYSIS_GAP,
					"Surefire reports not found — build may not have run tests");
		}
		RecordedCheck c = check != null ? check
				: RecordedCheck.fail("test_invariance", "Tests failed or count decreased");
		return new DiagnosticCheck(judgeName, c, GapCategory.AGENT_EXECUTION_GAP,
				"Agent broke existing tests during migration");
	}

	private DiagnosticCheck classifyDependencyVersion(String judgeName, @Nullable RecordedCheck check,
			@Nullable ExecutionPlan plan) {
		if (check == null) {
			return new DiagnosticCheck(judgeName, RecordedCheck.fail("dependency_version", "Version mismatch"),
					GapCategory.AGENT_EXECUTION_GAP, "Version upgrade not applied");
		}

		boolean planCoveredUpgrade = plan != null
				&& (plan.roadmapMarkdown().contains("pom-upgrader") || plan.roadmapMarkdown().contains("parent"));

		if (planCoveredUpgrade) {
			return new DiagnosticCheck(judgeName, check, GapCategory.AGENT_EXECUTION_GAP,
					"Plan included version upgrade but agent didn't apply it");
		}
		return new DiagnosticCheck(judgeName, check, GapCategory.PLAN_GENERATION_GAP,
				"Plan didn't include version upgrade step");
	}

	private DiagnosticCheck classifyAstDiff(String judgeName, RecordedJudgmentStatus status,
			@Nullable RecordedCheck check) {
		if (status == RecordedJudgmentStatus.ABSTAIN) {
			RecordedCheck c = check != null ? check : RecordedCheck.fail("ast_diff", "Missing beforeDir or data");
			return new DiagnosticCheck(judgeName, c, GapCategory.ANALYSIS_GAP,
					"ASTDiffJudge needs beforeDir metadata — wiring issue");
		}
		RecordedCheck c = check != null ? check : RecordedCheck.fail("ast_diff", "Unexpected AST changes");
		return new DiagnosticCheck(judgeName, c, GapCategory.AGENT_EXECUTION_GAP,
				"Agent made unexpected code changes beyond migration scope");
	}

	private DiagnosticCheck classifySemanticDiff(String judgeName, @Nullable RecordedCheck check) {
		if (check == null) {
			return new DiagnosticCheck(judgeName, RecordedCheck.fail("semantic_diff", "Semantic evaluation failed"),
					GapCategory.AGENT_EXECUTION_GAP, "Semantic criteria not met");
		}

		String checkName = check.name().toLowerCase();
		if (checkName.contains("compile") || checkName.contains("build") || checkName.contains("verify")) {
			return new DiagnosticCheck(judgeName, check, GapCategory.CRITERIA_GAP,
					"Criterion overlaps with deterministic judges — should be unique");
		}
		return new DiagnosticCheck(judgeName, check, GapCategory.AGENT_EXECUTION_GAP,
				"Agent didn't satisfy semantic criterion: " + check.name());
	}

	@Nullable
	private DiagnosticCheck classifyStructural(String judgeName, @Nullable RecordedCheck check, GapCategory category,
			String rationale) {
		if (check == null) {
			return null;
		}
		return new DiagnosticCheck(judgeName, check, category, rationale);
	}

	private boolean planCoversNamespace(String roadmap, String checkName) {
		String normalized = roadmap.toLowerCase();
		// Check name is like "javax.persistence removed" — extract the namespace
		String namespace = checkName.replace(" removed", "").trim().toLowerCase();
		return normalized.contains(namespace) || normalized.contains("javax-to-jakarta");
	}

}
