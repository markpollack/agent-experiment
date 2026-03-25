package io.github.markpollack.experiment.diagnostic;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.markpollack.journal.claude.PhaseCapture;
import io.github.markpollack.journal.claude.ToolResultRecord;

import io.github.markpollack.experiment.pipeline.AnalysisEnvelope;
import io.github.markpollack.experiment.pipeline.ExecutionPlan;
import org.junit.jupiter.api.Test;
import org.springaicommunity.judge.result.Check;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicReasonerTest {

	private final DeterministicReasoner reasoner = new DeterministicReasoner();

	// --- Rule PG-1: unused tool could have prevented failure ---

	@Test
	void pg1_pomUpgraderUnusedWithJavaxImports() {
		DiagnosticCheck check = check(GapCategory.PLAN_GENERATION_GAP, "Plan didn't include pom-upgrader");
		AnalysisEnvelope analysis = AnalysisEnvelope.builder()
			.projectName("petclinic")
			.importPatterns(Map.of("javax.persistence", List.of("Entity.java")))
			.build();
		ExecutionPlan plan = plan(List.of("javax-to-jakarta"));
		ReasoningContext context = context(analysis, plan, Set.of("javax-to-jakarta", "pom-upgrader"));

		RemediationReport report = reasoner.reason(diagnosticReport(check), context);

		assertThat(report.remediations()).hasSize(1);
		RemediationAction action = report.remediations().get(0);
		assertThat(action.target()).isEqualTo("planner-prompt");
		assertThat(action.actionType()).isEqualTo(ActionType.IMPROVE_PROMPT);
		assertThat(action.confidence()).isEqualTo(Confidence.DETERMINISTIC);
		assertThat(action.summary()).contains("pom-upgrader");
		assertThat(report.unresolvedChecks()).isEmpty();
	}

	@Test
	void pg1_allToolsUsed_noRemediation() {
		DiagnosticCheck check = check(GapCategory.PLAN_GENERATION_GAP, "Plan gap");
		AnalysisEnvelope analysis = AnalysisEnvelope.builder()
			.projectName("petclinic")
			.importPatterns(Map.of("javax.persistence", List.of("Entity.java")))
			.build();
		ExecutionPlan plan = plan(List.of("javax-to-jakarta", "pom-upgrader"));
		ReasoningContext context = context(analysis, plan, Set.of("javax-to-jakarta", "pom-upgrader"));

		RemediationReport report = reasoner.reason(diagnosticReport(check), context);

		// No unused tools → unresolved
		assertThat(report.remediations()).isEmpty();
		assertThat(report.unresolvedChecks()).hasSize(1);
	}

	@Test
	void pg1_noAnalysis_noRemediation() {
		DiagnosticCheck check = check(GapCategory.PLAN_GENERATION_GAP, "Plan gap");
		ReasoningContext context = context(null, plan(List.of()), Set.of("pom-upgrader"));

		RemediationReport report = reasoner.reason(diagnosticReport(check), context);

		// No analysis → can't determine if tool would help → falls through to generic
		// heuristic
		assertThat(report.remediations()).hasSize(1);
		assertThat(report.remediations().get(0).confidence()).isEqualTo(Confidence.HEURISTIC);
	}

	// --- Rule TG-1: implicit JDK dependency ---

	@Test
	void tg1_javaxXmlBindImportsNoExplicitDep() {
		DiagnosticCheck check = check(GapCategory.TOOL_GAP, "JAXB missing");
		AnalysisEnvelope analysis = AnalysisEnvelope.builder()
			.projectName("petclinic")
			.importPatterns(Map.of("javax.xml.bind.annotation", List.of("Vet.java", "Vets.java")))
			.build();
		ReasoningContext context = context(analysis, plan(List.of()), Set.of());

		RemediationReport report = reasoner.reason(diagnosticReport(check), context);

		assertThat(report.remediations()).hasSize(1);
		RemediationAction action = report.remediations().get(0);
		assertThat(action.target()).isEqualTo("pom-upgrader");
		assertThat(action.actionType()).isEqualTo(ActionType.ADD_RULE);
		assertThat(action.confidence()).isEqualTo(Confidence.DETERMINISTIC);
		assertThat(action.summary()).contains("javax.xml.bind");
	}

	@Test
	void tg1_javaxXmlBindWithExplicitDep_noRemediation() {
		DiagnosticCheck check = check(GapCategory.TOOL_GAP, "JAXB issue");
		AnalysisEnvelope analysis = AnalysisEnvelope.builder()
			.projectName("petclinic")
			.importPatterns(Map.of("javax.xml.bind.annotation", List.of("Vet.java")))
			.dependencies(Map.of("jakarta.xml.bind:jakarta.xml.bind-api", "4.0.0"))
			.build();
		ReasoningContext context = context(analysis, plan(List.of()), Set.of());

		RemediationReport report = reasoner.reason(diagnosticReport(check), context);

		assertThat(report.remediations()).isEmpty();
		assertThat(report.unresolvedChecks()).hasSize(1);
	}

	// --- Rule AG-1: build plugin not detected ---

	@Test
	void ag1_formatFailureNoBuildPlugins() {
		DiagnosticCheck check = new DiagnosticCheck("CommandJudge",
				Check.fail("spring_format", "Formatting violations"), GapCategory.ANALYSIS_GAP,
				"spring-javaformat-maven-plugin violations");
		AnalysisEnvelope analysis = AnalysisEnvelope.builder().projectName("petclinic").build();
		ReasoningContext context = context(analysis, plan(List.of()), Set.of());

		RemediationReport report = reasoner.reason(diagnosticReport(check), context);

		assertThat(report.remediations()).hasSize(1);
		RemediationAction action = report.remediations().get(0);
		assertThat(action.target()).isEqualTo("PomAnalyzer");
		assertThat(action.actionType()).isEqualTo(ActionType.ENHANCE_ANALYSIS);
		assertThat(action.confidence()).isEqualTo(Confidence.DETERMINISTIC);
	}

	@Test
	void ag1_formatFailureWithBuildPlugins_noRemediation() {
		DiagnosticCheck check = new DiagnosticCheck("CommandJudge",
				Check.fail("spring_format", "Formatting violations"), GapCategory.ANALYSIS_GAP,
				"formatting violation detected");
		AnalysisEnvelope analysis = AnalysisEnvelope.builder()
			.projectName("petclinic")
			.metadata(Map.of("buildPlugins", List.of("spring-javaformat-maven-plugin")))
			.build();
		ReasoningContext context = context(analysis, plan(List.of()), Set.of());

		RemediationReport report = reasoner.reason(diagnosticReport(check), context);

		assertThat(report.remediations()).isEmpty();
		assertThat(report.unresolvedChecks()).hasSize(1);
	}

	// --- Rule AE-1: repeated build errors ---

	@Test
	void ae1_repeatedErrors() {
		DiagnosticCheck check = check(GapCategory.AGENT_EXECUTION_GAP, "Build failed");
		String errorContent = "package jakarta.xml.bind.annotation does not exist - compilation error in Vet.java";
		PhaseCapture phase = phaseWithErrors(List.of(new ToolResultRecord("t1", errorContent, true),
				new ToolResultRecord("t2", errorContent, true)));
		ReasoningContext context = contextWithPhases(List.of(phase));

		RemediationReport report = reasoner.reason(diagnosticReport(check), context);

		// AE-1 fires from verdict check, TR-3 fires from trajectory
		assertThat(report.remediations()).anyMatch(a -> a.target().equals("agent-prompt")
				&& a.actionType() == ActionType.IMPROVE_PROMPT && a.confidence() == Confidence.HEURISTIC);
		assertThat(report.remediations())
			.anyMatch(a -> a.target().equals("pipeline") && a.actionType() == ActionType.ENHANCE_TOOL);
	}

	// --- Rule AG-2: generic unused tools ---

	@Test
	void ag2_agentExecutionGapWithUnusedTools() {
		DiagnosticCheck check = check(GapCategory.AGENT_EXECUTION_GAP, "Agent failed");
		ReasoningContext context = context(null, plan(List.of()), Set.of("pom-upgrader"));

		RemediationReport report = reasoner.reason(diagnosticReport(check), context);

		assertThat(report.remediations()).hasSize(1);
		assertThat(report.remediations().get(0).target()).isEqualTo("planner-prompt");
		assertThat(report.remediations().get(0).confidence()).isEqualTo(Confidence.HEURISTIC);
	}

	// --- Rule EG-1: judge calibration ---

	@Test
	void eg1_evaluationGap() {
		DiagnosticCheck check = new DiagnosticCheck("ASTDiffJudge", Check.fail("ast_diff", "Unexpected changes"),
				GapCategory.EVALUATION_GAP, "False positive on WebConfig");

		RemediationReport report = reasoner.reason(diagnosticReport(check), emptyContext());

		assertThat(report.remediations()).hasSize(1);
		RemediationAction action = report.remediations().get(0);
		assertThat(action.target()).isEqualTo("ASTDiffJudge");
		assertThat(action.actionType()).isEqualTo(ActionType.CALIBRATE_JUDGE);
		assertThat(action.confidence()).isEqualTo(Confidence.HEURISTIC);
	}

	// --- Deduplication ---

	@Test
	void deduplicatesSameRemediation() {
		DiagnosticCheck check1 = check(GapCategory.EVALUATION_GAP, "False positive 1");
		DiagnosticCheck check2 = check(GapCategory.EVALUATION_GAP, "False positive 2");

		RemediationReport report = reasoner.reason(diagnosticReport(check1, check2), emptyContext());

		// Both EG-1 would produce same target+actionType+summary → deduplicated
		assertThat(report.remediations()).hasSize(1);
	}

	// --- All passing ---

	@Test
	void allPassing_emptyRemediations() {
		DiagnosticReport diagnosticReport = new DiagnosticReport("exp-1", List.of(),
				GapDistribution.fromChecks(List.of()), List.of("No failures detected"));

		RemediationReport report = reasoner.reason(diagnosticReport, emptyContext());

		assertThat(report.remediations()).isEmpty();
		assertThat(report.unresolvedChecks()).isEmpty();
	}

	// --- Full scenario: all 3 errors from run 2351d0af ---

	@Test
	void fullScenario_run2351d0af() {
		// Error 1: pom-upgrader not selected (PLAN_GENERATION_GAP)
		DiagnosticCheck ehcacheCheck = check(GapCategory.PLAN_GENERATION_GAP,
				"Plan didn't include pom-upgrader for ehcache");

		// Error 3: JAXB implicit dependency (TOOL_GAP)
		DiagnosticCheck jaxbCheck = check(GapCategory.TOOL_GAP, "javax-to-jakarta missed JAXB deps");

		// Error 4: spring-javaformat not detected (ANALYSIS_GAP)
		DiagnosticCheck formatCheck = new DiagnosticCheck("CommandJudge",
				Check.fail("spring_format", "Formatting violations"), GapCategory.ANALYSIS_GAP,
				"spring-javaformat-maven-plugin enforcement not detected");

		AnalysisEnvelope analysis = AnalysisEnvelope.builder()
			.projectName("petclinic")
			.importPatterns(Map.of("javax.persistence", List.of("Owner.java"), "javax.xml.bind.annotation",
					List.of("Vet.java", "Vets.java")))
			.dependencies(Map.of("org.ehcache:ehcache", "3.10.0"))
			.build();
		ExecutionPlan plan = plan(List.of("javax-to-jakarta", "thymeleaf-migrator"));
		ReasoningContext context = context(analysis, plan,
				Set.of("javax-to-jakarta", "pom-upgrader", "thymeleaf-migrator"));

		RemediationReport report = reasoner.reason(diagnosticReport(ehcacheCheck, jaxbCheck, formatCheck), context);

		assertThat(report.remediations()).hasSize(3);

		// Verify each remediation maps to the expected target
		assertThat(report.remediations()).extracting(RemediationAction::target)
			.containsExactlyInAnyOrder("planner-prompt", "pom-upgrader", "PomAnalyzer");

		assertThat(report.remediations()).extracting(RemediationAction::actionType)
			.containsExactlyInAnyOrder(ActionType.IMPROVE_PROMPT, ActionType.ADD_RULE, ActionType.ENHANCE_ANALYSIS);

		assertThat(report.unresolvedChecks()).isEmpty();
	}

	// --- Trajectory rules: fire even when all judges pass ---

	@Test
	void tr1_unusedToolWithJavaxImports_allJudgesPass() {
		AnalysisEnvelope analysis = AnalysisEnvelope.builder()
			.projectName("petclinic")
			.importPatterns(Map.of("javax.persistence", List.of("Entity.java")))
			.dependencies(Map.of("org.ehcache:ehcache", "managed"))
			.build();
		ExecutionPlan plan = plan(List.of("javax-to-jakarta"));
		ReasoningContext context = context(analysis, plan, Set.of("javax-to-jakarta", "pom-upgrader"));

		// Empty diagnostic report — all judges passed
		DiagnosticReport emptyReport = new DiagnosticReport("exp-1",
				List.of(new ItemDiagnostic("item-1", List.of(), null)), GapDistribution.fromChecks(List.of()),
				List.of("No failures detected"));

		RemediationReport report = reasoner.reason(emptyReport, context);

		assertThat(report.remediations()).isNotEmpty();
		assertThat(report.remediations()).anyMatch(a -> a.target().equals("planner-prompt")
				&& a.actionType() == ActionType.IMPROVE_PROMPT && a.summary().contains("pom-upgrader"));
		// sourceCheck is null for trajectory rules
		assertThat(report.remediations()).allMatch(a -> a.sourceCheck() == null);
	}

	@Test
	void tr2_implicitJdkDep_allJudgesPass() {
		AnalysisEnvelope analysis = AnalysisEnvelope.builder()
			.projectName("petclinic")
			.importPatterns(Map.of("javax.xml.bind.annotation", List.of("Vet.java", "Vets.java")))
			.build();
		ReasoningContext context = context(analysis, plan(List.of("javax-to-jakarta")), Set.of("javax-to-jakarta"));

		DiagnosticReport emptyReport = new DiagnosticReport("exp-1",
				List.of(new ItemDiagnostic("item-1", List.of(), null)), GapDistribution.fromChecks(List.of()),
				List.of());

		RemediationReport report = reasoner.reason(emptyReport, context);

		assertThat(report.remediations()).anyMatch(a -> a.target().equals("pom-upgrader")
				&& a.actionType() == ActionType.ADD_RULE && a.summary().contains("javax.xml.bind"));
	}

	@Test
	void tr3_buildErrorsInTrajectory_allJudgesPass() {
		String errorContent = "[ERROR] package jakarta.xml.bind.annotation does not exist\n"
				+ "[ERROR] compilation failed in Vet.java";
		PhaseCapture phase = phaseWithErrors(List.of(new ToolResultRecord("t1", errorContent, true)));
		ReasoningContext context = contextWithPhases(List.of(phase));

		DiagnosticReport emptyReport = new DiagnosticReport("exp-1",
				List.of(new ItemDiagnostic("item-1", List.of(), null)), GapDistribution.fromChecks(List.of()),
				List.of());

		RemediationReport report = reasoner.reason(emptyReport, context);

		assertThat(report.remediations()).anyMatch(a -> a.target().equals("pipeline")
				&& a.actionType() == ActionType.ENHANCE_TOOL && a.summary().contains("build error"));
	}

	@Test
	void tr4_formatErrorInTrajectory_noBuildPlugins() {
		String errorContent = "[ERROR] Failed to execute goal spring-javaformat:validate\n"
				+ "Formatting violations found";
		PhaseCapture phase = phaseWithErrors(List.of(new ToolResultRecord("t1", errorContent, true)));
		AnalysisEnvelope analysis = AnalysisEnvelope.builder().projectName("petclinic").build();
		ReasoningContext context = new ReasoningContext(analysis, plan(List.of()), Set.of(), List.of(phase), null, null,
				List.of(), null, null);

		DiagnosticReport emptyReport = new DiagnosticReport("exp-1",
				List.of(new ItemDiagnostic("item-1", List.of(), null)), GapDistribution.fromChecks(List.of()),
				List.of());

		RemediationReport report = reasoner.reason(emptyReport, context);

		assertThat(report.remediations())
			.anyMatch(a -> a.target().equals("PomAnalyzer") && a.actionType() == ActionType.ENHANCE_ANALYSIS);
	}

	@Test
	void trajectoryRules_noErrorsNoUnusedTools_noRemediations() {
		ReasoningContext context = emptyContext();

		DiagnosticReport emptyReport = new DiagnosticReport("exp-1",
				List.of(new ItemDiagnostic("item-1", List.of(), null)), GapDistribution.fromChecks(List.of()),
				List.of());

		RemediationReport report = reasoner.reason(emptyReport, context);

		assertThat(report.remediations()).isEmpty();
	}

	@Test
	void trajectoryRules_deduplicateWithVerdictRules() {
		// PG-1 fires from verdict check AND TR-1 fires from trajectory — should
		// deduplicate
		DiagnosticCheck check = check(GapCategory.PLAN_GENERATION_GAP, "Plan gap");
		AnalysisEnvelope analysis = AnalysisEnvelope.builder()
			.projectName("petclinic")
			.importPatterns(Map.of("javax.persistence", List.of("Entity.java")))
			.build();
		ExecutionPlan plan = plan(List.of("javax-to-jakarta"));
		ReasoningContext context = context(analysis, plan, Set.of("javax-to-jakarta", "pom-upgrader"));

		RemediationReport report = reasoner.reason(diagnosticReport(check), context);

		// Both PG-1 and TR-1 produce "Planner should select pom-upgrader..." with same
		// key
		long pomUpgraderCount = report.remediations()
			.stream()
			.filter(a -> a.summary().contains("pom-upgrader"))
			.count();
		assertThat(pomUpgraderCount).isEqualTo(1);
	}

	// --- helpers ---

	private static DiagnosticCheck check(GapCategory category, String rationale) {
		return new DiagnosticCheck("TestJudge", Check.fail("test_check", "failure"), category, rationale);
	}

	private static ExecutionPlan plan(List<String> toolRecommendations) {
		return new ExecutionPlan("roadmap text", toolRecommendations, List.of(), 0.0, 0, 0, 0, 0, null);
	}

	private static ReasoningContext context(AnalysisEnvelope analysis, ExecutionPlan plan, Set<String> availableTools) {
		return new ReasoningContext(analysis, plan, availableTools, List.of(), null, null, List.of(), null, null);
	}

	private static ReasoningContext contextWithPhases(List<PhaseCapture> phases) {
		return new ReasoningContext(null, null, Set.of(), phases, null, null, List.of(), null, null);
	}

	private static ReasoningContext emptyContext() {
		return new ReasoningContext(null, null, Set.of(), List.of(), null, null, List.of(), null, null);
	}

	private static DiagnosticReport diagnosticReport(DiagnosticCheck... checks) {
		List<DiagnosticCheck> checkList = List.of(checks);
		ItemDiagnostic item = new ItemDiagnostic("item-1", checkList,
				checkList.isEmpty() ? null : checks[0].gapCategory());
		return new DiagnosticReport("exp-1", List.of(item), GapDistribution.fromChecks(checkList), List.of());
	}

	private static PhaseCapture phaseWithErrors(List<ToolResultRecord> toolResults) {
		return new PhaseCapture("execute", "prompt", 0, 0, 0, 0, 0, 0, 0, 0.0, "session-1", 1, false, "", List.of(),
				List.of(), null, toolResults);
	}

}
