package io.github.markpollack.experiment.diagnostic;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.markpollack.journal.claude.ToolResultRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rule-based diagnostic reasoner that inspects structured data (analysis envelope,
 * execution plan, available tools, trajectory) to produce remediation recommendations.
 *
 * <p>
 * Two rule categories:
 *
 * <p>
 * <b>Verdict rules</b> — fire on failing DiagnosticChecks from the jury:
 * <ul>
 * <li><b>PG-1</b>: Unused tool could have prevented failure</li>
 * <li><b>TG-1</b>: Implicit JDK dependency needs explicit Maven dep</li>
 * <li><b>AG-1</b>: Build plugin not detected by analysis</li>
 * <li><b>AE-1</b>: Repeated build errors from same root cause</li>
 * <li><b>AG-2</b>: Generic agent execution with unused tools</li>
 * <li><b>EG-1</b>: Judge penalizes legitimate changes</li>
 * </ul>
 *
 * <p>
 * <b>Trajectory rules</b> — fire on execution context regardless of judge outcomes. These
 * identify efficiency gaps: the agent succeeded but wasted cycles on problems that better
 * deterministic tools could have prevented:
 * <ul>
 * <li><b>TR-1</b>: Unused tool with analysis signals (planner missed an available
 * tool)</li>
 * <li><b>TR-2</b>: Implicit JDK dependency pattern (imports without Maven dep)</li>
 * <li><b>TR-3</b>: Build errors during execution (agent recovered but shouldn't have
 * needed to)</li>
 * <li><b>TR-4</b>: Build plugin not detected by analysis (formatting errors in
 * trajectory)</li>
 * </ul>
 */
public class DeterministicReasoner implements DiagnosticReasoner {

	private static final Logger logger = LoggerFactory.getLogger(DeterministicReasoner.class);

	private static final Set<String> IMPLICIT_JDK_PACKAGES = Set.of("javax.xml.bind", "javax.xml.ws",
			"javax.annotation", "javax.activation");

	@Override
	public RemediationReport reason(DiagnosticReport report, ReasoningContext context) {
		List<RemediationAction> remediations = new ArrayList<>();
		List<DiagnosticCheck> unresolved = new ArrayList<>();

		Set<String> dedup = new LinkedHashSet<>();

		for (ItemDiagnostic item : report.items()) {
			for (DiagnosticCheck check : item.checks()) {
				List<RemediationAction> actions = applyRules(check, context);
				if (actions.isEmpty()) {
					unresolved.add(check);
				}
				else {
					for (RemediationAction action : actions) {
						String key = action.target() + "|" + action.actionType() + "|" + action.summary();
						if (dedup.add(key)) {
							remediations.add(action);
						}
					}
				}
			}
		}

		// Phase 2: Trajectory rules — fire on execution context regardless of judge
		// outcomes.
		// These identify efficiency gaps where the agent recovered but deterministic
		// tools
		// could have prevented the problem entirely.
		applyTrajectoryRules(context, remediations, dedup);

		logger.debug(
				"DeterministicReasoner produced {} remediations ({} from trajectory), {} unresolved from {} checks",
				remediations.size(), remediations.stream().filter(a -> a.sourceCheck() == null).count(),
				unresolved.size(), report.items().stream().mapToInt(i -> i.checks().size()).sum());

		return new RemediationReport(report.experimentId(), remediations, List.of(), unresolved);
	}

	private List<RemediationAction> applyRules(DiagnosticCheck check, ReasoningContext context) {
		if (check.gapCategory() == null) {
			return List.of();
		}

		List<RemediationAction> actions = new ArrayList<>();

		switch (check.gapCategory()) {
			case PLAN_GENERATION_GAP -> {
				rulePG1(check, context, actions);
			}
			case TOOL_GAP -> {
				ruleTG1(check, context, actions);
			}
			case ANALYSIS_GAP -> {
				ruleAG1(check, context, actions);
			}
			case AGENT_EXECUTION_GAP -> {
				ruleAE1(check, context, actions);
				if (actions.isEmpty()) {
					ruleAG2(check, context, actions);
				}
			}
			case EVALUATION_GAP -> {
				ruleEG1(check, actions);
			}
			default -> {
				// KB_GAP, CRITERIA_GAP, STOCHASTICITY_GAP — no deterministic rule yet
			}
		}

		return actions;
	}

	/**
	 * Rule PG-1: Unused tool could have prevented failure.
	 * <p>
	 * Trigger: PLAN_GENERATION_GAP + available tools exist that weren't selected +
	 * analysis shows signals the tool would act on.
	 */
	private void rulePG1(DiagnosticCheck check, ReasoningContext context, List<RemediationAction> actions) {
		Set<String> unused = context.unusedTools();
		if (unused.isEmpty()) {
			return;
		}

		// Check if pom-upgrader was unused and analysis has javax imports or managed deps
		if (context.analysis() != null && unused.contains("pom-upgrader")) {
			Map<String, List<String>> imports = context.analysis().importPatterns();
			Map<String, String> deps = context.analysis().dependencies();

			boolean hasJavaxImports = imports.keySet().stream().anyMatch(k -> k.startsWith("javax."));
			boolean hasManagedDeps = !deps.isEmpty();

			if (hasJavaxImports || hasManagedDeps) {
				actions.add(new RemediationAction("planner-prompt", ActionType.IMPROVE_PROMPT,
						"Planner should select pom-upgrader when dependencies need migration",
						"pom-upgrader was available but not selected by the planner. "
								+ "Analysis shows javax imports or managed dependencies that pom-upgrader handles. "
								+ "The planner prompt should emphasize selecting pom-upgrader when the analysis "
								+ "envelope contains javax.* import patterns or Spring Boot parent dependencies.",
						Confidence.DETERMINISTIC, check));
				return;
			}
		}

		// Generic: any unused tool + plan generation gap
		if (!unused.isEmpty()) {
			actions.add(new RemediationAction("planner-prompt", ActionType.IMPROVE_PROMPT,
					"Planner did not consider all available tools: " + unused,
					"Available tools " + unused + " were not included in the execution plan. "
							+ "The planner prompt should enumerate available tools and justify why each "
							+ "was included or excluded.",
					Confidence.HEURISTIC, check));
		}
	}

	/**
	 * Rule TG-1: Implicit JDK dependency needs explicit Maven dep.
	 * <p>
	 * Trigger: TOOL_GAP + analysis has imports from packages that were in JDK 8 but
	 * removed in 11+ + no corresponding Maven dependency exists.
	 */
	private void ruleTG1(DiagnosticCheck check, ReasoningContext context, List<RemediationAction> actions) {
		if (context.analysis() == null) {
			return;
		}

		Map<String, List<String>> imports = context.analysis().importPatterns();
		Map<String, String> deps = context.analysis().dependencies();

		for (String implicitPkg : IMPLICIT_JDK_PACKAGES) {
			boolean hasImports = imports.keySet().stream().anyMatch(k -> k.startsWith(implicitPkg));
			if (!hasImports) {
				continue;
			}

			// Check if a corresponding Jakarta/explicit dep exists
			boolean hasExplicitDep = deps.keySet()
				.stream()
				.anyMatch(k -> k.contains("jaxb") || k.contains("xml.bind") || k.contains("javax.annotation")
						|| k.contains("activation"));
			if (hasExplicitDep) {
				continue;
			}

			actions.add(new RemediationAction("pom-upgrader", ActionType.ADD_RULE,
					"Add ImplicitDependencyAddition rule for " + implicitPkg,
					"Project uses " + implicitPkg + " imports (implicit in JDK 8, removed in JDK 11+) "
							+ "but has no explicit Maven dependency for it. pom-upgrader should have an "
							+ "ImplicitDependencyAddition rule that detects these imports and adds the "
							+ "corresponding Jakarta dependency (e.g., jakarta.xml.bind-api + jaxb-runtime).",
					Confidence.DETERMINISTIC, check));
		}
	}

	/**
	 * Rule AG-1: Build plugin not detected by analysis.
	 * <p>
	 * Trigger: ANALYSIS_GAP or PLAN_GENERATION_GAP + check mentions format/style/enforce
	 * + analysis metadata has no "buildPlugins" key.
	 */
	private void ruleAG1(DiagnosticCheck check, ReasoningContext context, List<RemediationAction> actions) {
		String rationale = check.rationale() != null ? check.rationale().toLowerCase() : "";
		String checkName = check.check().name() != null ? check.check().name().toLowerCase() : "";

		boolean mentionsFormat = rationale.contains("format") || rationale.contains("style")
				|| rationale.contains("enforce") || checkName.contains("format") || checkName.contains("style");

		if (!mentionsFormat) {
			return;
		}

		if (context.analysis() != null && context.analysis().metadata().containsKey("buildPlugins")) {
			return;
		}

		actions.add(new RemediationAction("PomAnalyzer", ActionType.ENHANCE_ANALYSIS,
				"Extract build plugins from POM (spring-javaformat, enforcer, etc.)",
				"A formatting/style violation occurred but PomAnalyzer does not detect build plugins. "
						+ "Adding build plugin extraction to PomAnalyzer would allow the planner to include "
						+ "formatter steps in the roadmap, preventing violations at verify time.",
				Confidence.DETERMINISTIC, check));
	}

	/**
	 * Rule AE-1: Repeated build errors from same root cause.
	 * <p>
	 * Trigger: AGENT_EXECUTION_GAP + multiple isError=true Bash results with similar
	 * content.
	 */
	private void ruleAE1(DiagnosticCheck check, ReasoningContext context, List<RemediationAction> actions) {
		List<ToolResultRecord> errors = context.errorToolResults();
		if (errors.size() < 2) {
			return;
		}

		// Check for similar error content (simple substring match)
		for (int i = 0; i < errors.size(); i++) {
			String content1 = errors.get(i).content();
			if (content1 == null || content1.length() < 20) {
				continue;
			}
			// Take a distinctive substring to match
			String snippet = content1.substring(0, Math.min(80, content1.length()));
			for (int j = i + 1; j < errors.size(); j++) {
				String content2 = errors.get(j).content();
				if (content2 != null && content2.contains(snippet)) {
					actions.add(new RemediationAction("agent-prompt", ActionType.IMPROVE_PROMPT,
							"Agent retried failed build without fully fixing root cause",
							"Multiple tool results contain the same error content, suggesting "
									+ "the agent retried a failing build without addressing the root cause. "
									+ "Consider adding a reflexion gate that requires the agent to identify "
									+ "and explain the root cause before retrying.",
							Confidence.HEURISTIC, check));
					return;
				}
			}
		}
	}

	/**
	 * Rule AG-2: Generic agent execution with unused tools.
	 * <p>
	 * Trigger: AGENT_EXECUTION_GAP + unused tools exist.
	 */
	private void ruleAG2(DiagnosticCheck check, ReasoningContext context, List<RemediationAction> actions) {
		Set<String> unused = context.unusedTools();
		if (unused.isEmpty()) {
			return;
		}

		actions.add(new RemediationAction("planner-prompt", ActionType.IMPROVE_PROMPT,
				"Ensure planner considers all available tools",
				"Agent execution gap occurred while tools " + unused + " were available but unused. "
						+ "The planner should evaluate whether these tools could prevent execution failures.",
				Confidence.HEURISTIC, check));
	}

	/**
	 * Rule EG-1: Judge penalizes legitimate changes.
	 * <p>
	 * Trigger: EVALUATION_GAP.
	 */
	private void ruleEG1(DiagnosticCheck check, List<RemediationAction> actions) {
		actions.add(new RemediationAction(check.judgeName(), ActionType.CALIBRATE_JUDGE,
				"Calibrate " + check.judgeName() + " — may penalize legitimate changes",
				"Judge " + check.judgeName() + " flagged a potential false positive. "
						+ "Review the judge's scoring against a gold set and consider adding "
						+ "a migration-aware scoring mode that distinguishes intentional structural "
						+ "changes from accidental ones.",
				Confidence.HEURISTIC, check));
	}

	// ---- Trajectory rules: fire on context, not on failing checks ----

	/**
	 * Apply trajectory rules that inspect the execution context directly. These produce
	 * remediations even when all judges pass — they identify efficiency gaps where
	 * deterministic tools could have prevented wasted agent cycles.
	 */
	private void applyTrajectoryRules(ReasoningContext context, List<RemediationAction> remediations,
			Set<String> dedup) {
		List<RemediationAction> trajectoryActions = new ArrayList<>();
		ruleTR1(context, trajectoryActions);
		ruleTR2(context, trajectoryActions);
		ruleTR3(context, trajectoryActions);
		ruleTR4(context, trajectoryActions);

		for (RemediationAction action : trajectoryActions) {
			String key = action.target() + "|" + action.actionType() + "|" + action.summary();
			if (dedup.add(key)) {
				remediations.add(action);
			}
		}
	}

	/**
	 * Rule TR-1: Unused tool with analysis signals.
	 * <p>
	 * Trigger: pom-upgrader available but not selected + analysis has javax imports or
	 * managed dependencies. The planner missed a tool that could have prevented build
	 * errors.
	 */
	private void ruleTR1(ReasoningContext context, List<RemediationAction> actions) {
		Set<String> unused = context.unusedTools();
		if (unused.isEmpty() || context.analysis() == null) {
			return;
		}

		if (unused.contains("pom-upgrader")) {
			Map<String, List<String>> imports = context.analysis().importPatterns();
			Map<String, String> deps = context.analysis().dependencies();

			boolean hasJavaxImports = imports.keySet().stream().anyMatch(k -> k.startsWith("javax."));
			boolean hasManagedDeps = !deps.isEmpty();

			if (hasJavaxImports || hasManagedDeps) {
				actions.add(new RemediationAction("planner-prompt", ActionType.IMPROVE_PROMPT,
						"Planner should select pom-upgrader when dependencies need migration",
						"pom-upgrader was available but not selected by the planner. "
								+ "Analysis shows javax imports ("
								+ imports.keySet().stream().filter(k -> k.startsWith("javax.")).toList() + ") and "
								+ deps.size() + " managed dependencies. "
								+ "pom-upgrader handles ehcache classifier, dependency version upgrades, "
								+ "and dependency coordinate remapping that the agent otherwise discovers "
								+ "through trial-and-error build failures.",
						Confidence.DETERMINISTIC, null));
			}
		}
	}

	/**
	 * Rule TR-2: Implicit JDK dependency pattern.
	 * <p>
	 * Trigger: analysis has imports from packages that were in JDK 8 but removed in JDK
	 * 11+ (e.g. javax.xml.bind) and no corresponding Maven dependency exists. The agent
	 * will discover this via build failure after javax-to-jakarta renames the imports.
	 */
	private void ruleTR2(ReasoningContext context, List<RemediationAction> actions) {
		if (context.analysis() == null) {
			return;
		}

		Map<String, List<String>> imports = context.analysis().importPatterns();
		Map<String, String> deps = context.analysis().dependencies();

		for (String implicitPkg : IMPLICIT_JDK_PACKAGES) {
			boolean hasImports = imports.keySet().stream().anyMatch(k -> k.startsWith(implicitPkg));
			if (!hasImports) {
				continue;
			}

			boolean hasExplicitDep = deps.keySet()
				.stream()
				.anyMatch(k -> k.contains("jaxb") || k.contains("xml.bind") || k.contains("javax.annotation")
						|| k.contains("activation"));
			if (hasExplicitDep) {
				continue;
			}

			List<String> affectedFiles = imports.entrySet()
				.stream()
				.filter(e -> e.getKey().startsWith(implicitPkg))
				.flatMap(e -> e.getValue().stream())
				.toList();

			actions.add(new RemediationAction("pom-upgrader", ActionType.ADD_RULE,
					"Add ImplicitDependencyAddition rule for " + implicitPkg,
					"Project uses " + implicitPkg + " imports in " + affectedFiles.size() + " files " + affectedFiles
							+ " but has no explicit Maven dependency. This package was bundled in JDK 8 "
							+ "and removed in JDK 11+. After javax-to-jakarta renames the imports, the build "
							+ "will fail because the jakarta.* classes aren't on the classpath. "
							+ "pom-upgrader should detect this pattern and add the corresponding "
							+ "Jakarta dependency (e.g., jakarta.xml.bind-api + jaxb-runtime).",
					Confidence.DETERMINISTIC, null));
		}
	}

	/**
	 * Rule TR-3: Build errors during execution.
	 * <p>
	 * Trigger: error tool results present in the execution trajectory. The agent
	 * recovered, but each error represents a wasted build cycle (~30s) that a better
	 * deterministic tool or planner prompt could have prevented.
	 */
	private void ruleTR3(ReasoningContext context, List<RemediationAction> actions) {
		List<ToolResultRecord> errors = context.errorToolResults();
		if (errors.isEmpty()) {
			return;
		}

		// Summarize the error content
		List<String> errorSnippets = new ArrayList<>();
		for (ToolResultRecord error : errors) {
			String content = error.content();
			if (content != null && !content.isBlank()) {
				// Extract the first meaningful line (skip "Exit code 1" and Maven
				// boilerplate)
				String snippet = extractErrorSignal(content);
				if (snippet != null) {
					errorSnippets.add(snippet);
				}
			}
		}

		actions.add(new RemediationAction("pipeline", ActionType.ENHANCE_TOOL,
				"Agent recovered from " + errors.size() + " build error(s) — deterministic tools should handle these",
				"The agent succeeded (all judges passed) but encountered " + errors.size()
						+ " build errors during execution. Each error represents a wasted build cycle "
						+ "that better deterministic tooling could prevent. Error signals: " + errorSnippets
						+ ". Review each error to determine if a deterministic tool (pom-upgrader, "
						+ "javax-to-jakarta, PomAnalyzer) could be enhanced to prevent it.",
				Confidence.HEURISTIC, null));
	}

	/**
	 * Rule TR-4: Build plugin not detected by analysis.
	 * <p>
	 * Trigger: error tool results contain formatting/style-related errors + PomAnalyzer
	 * metadata has no "buildPlugins" key. The planner couldn't know about formatting
	 * enforcement because the analysis didn't detect it.
	 */
	private void ruleTR4(ReasoningContext context, List<RemediationAction> actions) {
		if (context.analysis() != null && context.analysis().metadata().containsKey("buildPlugins")) {
			return;
		}

		List<ToolResultRecord> errors = context.errorToolResults();
		boolean hasFormatError = errors.stream().anyMatch(e -> {
			String content = e.content();
			return content != null && (content.contains("javaformat") || content.contains("spring-javaformat")
					|| content.contains("Formatting violations") || content.contains("checkstyle"));
		});

		if (!hasFormatError) {
			return;
		}

		actions.add(new RemediationAction("PomAnalyzer", ActionType.ENHANCE_ANALYSIS,
				"Extract build plugins from POM (spring-javaformat, enforcer, etc.)",
				"A formatting/style error appeared in the build trajectory but PomAnalyzer does not "
						+ "detect build plugins. Adding build plugin extraction would allow the planner to "
						+ "include a formatter step in the roadmap, preventing the error cycle entirely.",
				Confidence.DETERMINISTIC, null));
	}

	/**
	 * Extract the most informative signal from a Maven build error output.
	 */
	@org.jspecify.annotations.Nullable
	private String extractErrorSignal(String content) {
		// Look for [ERROR] lines which carry the actual failure message
		for (String line : content.split("\n")) {
			String trimmed = line.trim();
			if (trimmed.startsWith("[ERROR]") && !trimmed.contains("See ") && !trimmed.contains("Re-run")
					&& !trimmed.contains("For more information") && !trimmed.contains("-> [Help")
					&& trimmed.length() > 10) {
				return trimmed.substring(0, Math.min(150, trimmed.length()));
			}
		}
		// Fallback: first 100 chars
		return content.substring(0, Math.min(100, content.length())).replace("\n", " ");
	}

}
