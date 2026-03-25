package io.github.markpollack.experiment.pipeline.claude;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.markpollack.experiment.pipeline.AnalysisEnvelope;
import io.github.markpollack.experiment.pipeline.AnnotationUsage;
import io.github.markpollack.experiment.pipeline.ExecutionPlan;
import io.github.markpollack.experiment.pipeline.PlanConfig;
import io.github.markpollack.experiment.pipeline.PlanGenerationException;
import io.github.markpollack.experiment.pipeline.PlanGenerator;
import io.github.markpollack.journal.claude.PhaseCapture;
import io.github.markpollack.journal.claude.SessionLogParser;
import io.github.markpollack.journal.claude.ToolUseRecord;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.claude.agent.sdk.ClaudeClient;
import org.springaicommunity.claude.agent.sdk.ClaudeSyncClient;
import org.springaicommunity.claude.agent.sdk.config.PermissionMode;
import org.springaicommunity.claude.agent.sdk.transport.CLIOptions;

/**
 * {@link PlanGenerator} implementation using Claude SDK for 2-phase planning.
 *
 * <p>
 * Phase 1 (Explore): Claude reads the SAE summary and knowledge store, summarizes
 * applicable migration patterns.
 * <p>
 * Phase 2 (Plan): Claude generates a Forge-style roadmap with explicit tool commands.
 *
 * <p>
 * Prompt patterns adapted from the POC's {@code UpgradePlanner} in
 * refactoring-agent/tools/spring-upgrade-agent.
 */
public class ClaudePlanGenerator implements PlanGenerator {

	private static final Logger logger = LoggerFactory.getLogger(ClaudePlanGenerator.class);

	private static final Pattern TOOL_NAME_PATTERN = Pattern
		.compile("\\b(javax-to-jakarta|pom-upgrader|thymeleaf-migrator"
				+ "|junit-migrator|annotation-migrator|property-migrator)\\b");

	private final @Nullable Path workingDirectory;

	/**
	 * @param workingDirectory working directory for the Claude process (nullable — uses
	 * current directory). Set to the knowledge store root so Claude can read knowledge
	 * files.
	 */
	public ClaudePlanGenerator(@Nullable Path workingDirectory) {
		this.workingDirectory = workingDirectory;
	}

	public ClaudePlanGenerator() {
		this(null);
	}

	@Override
	public ExecutionPlan generate(AnalysisEnvelope analysis, PlanConfig config) throws PlanGenerationException {
		long startMs = System.currentTimeMillis();

		CLIOptions options = buildOptions(config);

		try (ClaudeSyncClient client = buildClient(options)) {
			// Phase 1: Explore
			String explorePrompt = buildExplorePrompt(analysis, config);
			logger.info("Planning phase 1 (explore): {} chars prompt", explorePrompt.length());
			client.connect(explorePrompt);
			Path exploreTrace = config.runDir() != null ? config.runDir().resolve("agent-trace-explore.jsonl") : null;
			PhaseCapture exploreCapture = SessionLogParser.parse(client.receiveResponse(), "explore", explorePrompt,
					exploreTrace);
			logger.info("Explore complete: {} input, {} output tokens", exploreCapture.inputTokens(),
					exploreCapture.outputTokens());

			// Phase 2: Plan
			String planPrompt = buildPlanPrompt(analysis, config);
			logger.info("Planning phase 2 (plan): {} chars prompt", planPrompt.length());
			client.query(planPrompt);
			Path planTrace = config.runDir() != null ? config.runDir().resolve("agent-trace-plan.jsonl") : null;
			PhaseCapture planCapture = SessionLogParser.parse(client.receiveResponse(), "plan", planPrompt, planTrace);
			logger.info("Plan complete: {} input, {} output tokens", planCapture.inputTokens(),
					planCapture.outputTokens());

			String roadmapMarkdown = planCapture.textOutput();
			if (roadmapMarkdown == null || roadmapMarkdown.isBlank()) {
				throw new PlanGenerationException("Claude produced empty roadmap output");
			}

			List<String> toolRecommendations = extractToolRecommendations(roadmapMarkdown);
			List<String> kbFilesRead = extractKbFilesRead(List.of(exploreCapture, planCapture), config.knowledgeDir());

			int totalInput = exploreCapture.inputTokens() + planCapture.inputTokens();
			int totalOutput = exploreCapture.outputTokens() + planCapture.outputTokens();
			int totalThinking = exploreCapture.thinkingTokens() + planCapture.thinkingTokens();
			double totalCost = exploreCapture.totalCostUsd() + planCapture.totalCostUsd();
			long durationMs = System.currentTimeMillis() - startMs;
			String sessionId = planCapture.sessionId();

			logger.info("Planning complete: {} tools recommended, {} KB files read, cost=${}, duration={}ms",
					toolRecommendations.size(), kbFilesRead.size(), String.format("%.4f", totalCost), durationMs);

			return new ExecutionPlan(roadmapMarkdown, toolRecommendations, kbFilesRead, totalCost, totalInput,
					totalOutput, totalThinking, durationMs, sessionId);

		}
		catch (PlanGenerationException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new PlanGenerationException("Claude planning session failed: " + ex.getMessage(), ex);
		}
	}

	CLIOptions buildOptions(PlanConfig config) {
		String resolvedModel = resolveModelId(config.model());
		CLIOptions.Builder builder = CLIOptions.builder()
			.permissionMode(PermissionMode.DANGEROUSLY_SKIP_PERMISSIONS)
			.model(resolvedModel)
			.appendSystemPrompt(buildSystemPrompt())
			.timeout(config.timeout());
		return builder.build();
	}

	ClaudeSyncClient buildClient(CLIOptions options) {
		ClaudeClient.SyncSpecWithOptions spec = ClaudeClient.sync(options);
		if (workingDirectory != null) {
			spec.workingDirectory(workingDirectory);
		}
		return spec.build();
	}

	String buildSystemPrompt() {
		return """
				You are a senior Java developer specializing in Spring Boot migrations. \
				Your expertise covers Spring Boot 2 to 3 upgrades, Jakarta EE namespace \
				migration (javax to jakarta), Spring Security 5 to 6, and Maven build \
				system management.

				Your approach is methodical:
				1. Read available documentation and knowledge store files before planning
				2. Consider the specific project context (dependencies, imports, annotations)
				3. Provide step-by-step migration instructions ordered by dependency
				4. Include explicit tool invocation commands where applicable
				5. Compile and verify at stage boundaries, not after every edit""";
	}

	String buildExplorePrompt(AnalysisEnvelope analysis, PlanConfig config) {
		StringBuilder prompt = new StringBuilder();

		prompt.append("# Phase 1: Project Analysis and Knowledge Exploration\n\n");
		prompt.append("Analyze the following project and knowledge store to prepare for generating ");
		prompt.append("a migration roadmap.\n\n");

		// Knowledge store section
		if (config.knowledgeDir() != null) {
			prompt.append("## Knowledge Store\n\n");
			prompt.append("A knowledge store is available at: `").append(config.knowledgeDir()).append("`\n\n");
			prompt.append("1. Read the `index.md` file at the knowledge store root\n");
			prompt.append("2. Based on the project analysis below, self-select which knowledge files ");
			prompt.append("are relevant to this migration\n");
			prompt.append("3. Read the relevant knowledge files and note applicable migration patterns\n\n");
		}

		// Project analysis (SAE) section
		prompt.append("## Project Analysis (Machine-Generated)\n\n");
		prompt.append(formatAnalysisForPrompt(analysis));

		// Target versions
		if (config.targetBootVersion() != null || config.targetJavaVersion() != null) {
			prompt.append("\n## Migration Target\n\n");
			if (config.targetBootVersion() != null) {
				prompt.append("- **Target Spring Boot version**: ").append(config.targetBootVersion()).append('\n');
			}
			if (config.targetJavaVersion() != null) {
				prompt.append("- **Target Java version**: ").append(config.targetJavaVersion()).append('\n');
			}
			prompt.append('\n');
		}

		// Available tools
		if (!config.toolPaths().isEmpty()) {
			prompt.append("## Available Migration Tools\n\n");
			prompt.append("The following deterministic migration tools are available as CLI JARs. ");
			prompt.append("Note their capabilities for the planning phase:\n\n");
			for (Map.Entry<String, Path> entry : config.toolPaths().entrySet()) {
				prompt.append("- **")
					.append(entry.getKey())
					.append("**: `java -jar ")
					.append(entry.getValue())
					.append("`\n");
			}
			prompt.append('\n');
		}

		// Task
		prompt.append("## Task\n\n");
		prompt.append("Summarize your findings:\n");
		prompt.append("1. What migration steps are needed for this project?\n");
		prompt.append("2. Which knowledge store files are most relevant?\n");
		prompt.append("3. Which tools should be used, and in what order?\n");
		prompt.append("4. Are there any project-specific considerations or risks?\n\n");
		prompt.append("**Important**: Do NOT generate the roadmap yet. Just summarize your analysis. ");
		prompt.append("The roadmap will be requested in the next message.\n");

		return prompt.toString();
	}

	String buildPlanPrompt(AnalysisEnvelope analysis, PlanConfig config) {
		StringBuilder prompt = new StringBuilder();

		prompt.append("# Phase 2: Generate Migration Roadmap\n\n");
		prompt.append("Based on your analysis, generate a complete migration roadmap in Markdown format.\n\n");

		prompt.append("## Roadmap Format Requirements\n\n");
		prompt.append("Follow this structure exactly:\n\n");
		prompt.append("```\n");
		prompt.append("# Migration Roadmap: [Project Name]\n");
		prompt.append("Source: [current version] → Target: [target version]\n\n");
		prompt.append("## Overview\n");
		prompt.append("[1-2 paragraph summary of the migration scope]\n\n");
		prompt.append("## Stage 1: [Stage Name]\n\n");
		prompt.append("### Step 1.1: [Step Name]\n");
		prompt.append("- [ ] Work item 1\n");
		prompt.append("- [ ] Work item 2\n");
		prompt.append("- [ ] RUN tool-name [args] (for tool invocations)\n");
		prompt.append("- [ ] VERIFY: ./mvnw clean verify\n");
		prompt.append("```\n\n");

		prompt.append("## Rules\n\n");
		prompt.append("1. Use `- [ ]` checkboxes for all work items\n");
		prompt.append(
				"2. Use `RUN` prefix for tool invocations with full `java -jar /path/to/tool.jar [args]` command\n");
		prompt.append("3. Include `VERIFY:` criteria at stage boundaries\n");
		prompt.append("4. Order steps by dependency — things that other steps depend on come first\n");
		prompt.append("5. Group related changes into stages (e.g., 'POM Changes', 'Namespace Migration', ");
		prompt.append("'Code Changes', 'Build Verification')\n");
		prompt.append("6. For each tool invocation, explain what it does and why it's needed\n");
		prompt.append("7. Do NOT include steps for JDK installation or environment setup\n");
		prompt.append("8. Do NOT use the Write tool or create files — only generate the roadmap text\n\n");

		prompt.append("## VERIFY Criteria Requirements\n\n");
		prompt.append("Each `VERIFY:` criterion must be **unique** and test a **different aspect** ");
		prompt.append("of the migration. Criteria are evaluated by inspecting the workspace ");
		prompt.append("(file contents, build output), not by running the project interactively.\n\n");
		prompt.append("**Good example** (diverse criteria):\n");
		prompt.append("```\n");
		prompt.append("- [ ] VERIFY: ./mvnw clean verify (build and tests pass)\n");
		prompt.append("- [ ] VERIFY: No javax.* imports remain in src/main/java\n");
		prompt.append("- [ ] VERIFY: pom.xml parent version is 3.x\n");
		prompt.append(
				"- [ ] VERIFY: application.properties uses spring.jpa.* keys (not spring.datasource.initialization-mode)\n");
		prompt.append("```\n\n");
		prompt.append("**Bad example** (redundant criteria — DO NOT do this):\n");
		prompt.append("```\n");
		prompt.append("- [ ] VERIFY: ./mvnw clean verify\n");
		prompt.append("- [ ] VERIFY: ./mvnw clean compile\n");
		prompt.append("- [ ] VERIFY: ./mvnw clean verify (ensure build succeeds)\n");
		prompt.append("```\n\n");

		// Remind about available tools
		if (!config.toolPaths().isEmpty()) {
			prompt.append("## Available Tools (reminder)\n\n");
			for (Map.Entry<String, Path> entry : config.toolPaths().entrySet()) {
				prompt.append("- **")
					.append(entry.getKey())
					.append("**: `java -jar ")
					.append(entry.getValue())
					.append("`\n");
				prompt.append(describeToolPurpose(entry.getKey()));
			}
			prompt.append('\n');
		}

		prompt.append("Now generate the complete migration roadmap.\n");

		return prompt.toString();
	}

	private String formatAnalysisForPrompt(AnalysisEnvelope analysis) {
		StringBuilder sb = new StringBuilder();
		sb.append("- **Project**: ").append(analysis.projectName()).append('\n');
		if (analysis.bootVersion() != null) {
			sb.append("- **Spring Boot version**: ").append(analysis.bootVersion()).append('\n');
		}
		if (analysis.javaVersion() != null) {
			sb.append("- **Java version**: ").append(analysis.javaVersion()).append('\n');
		}
		sb.append("- **Build tool**: ").append(analysis.buildTool()).append('\n');
		if (analysis.parentCoordinates() != null) {
			sb.append("- **Parent POM**: ").append(analysis.parentCoordinates()).append('\n');
		}
		if (!analysis.modules().isEmpty()) {
			sb.append("- **Modules**: ").append(String.join(", ", analysis.modules())).append('\n');
		}

		if (!analysis.dependencies().isEmpty()) {
			sb.append("\n### Dependencies (").append(analysis.dependencies().size()).append(")\n\n");
			analysis.dependencies()
				.forEach((key, version) -> sb.append("- `").append(key).append("` : ").append(version).append('\n'));
		}

		if (!analysis.importPatterns().isEmpty()) {
			sb.append("\n### javax.* Import Patterns\n\n");
			analysis.importPatterns().forEach((namespace, files) -> {
				sb.append("- **").append(namespace).append("** — ").append(files.size()).append(" files: ");
				sb.append(String.join(", ", files)).append('\n');
			});
		}

		if (!analysis.annotations().isEmpty()) {
			sb.append("\n### Annotations Found\n\n");
			for (AnnotationUsage ann : analysis.annotations()) {
				sb.append("- `@").append(ann.annotation()).append("` in ").append(ann.file());
				if (ann.className() != null) {
					sb.append(" (").append(ann.className()).append(')');
				}
				sb.append('\n');
			}
		}

		if (!analysis.configFiles().isEmpty()) {
			sb.append("\n### Configuration Files\n\n");
			analysis.configFiles().forEach(f -> sb.append("- ").append(f).append('\n'));
		}

		return sb.toString();
	}

	private String describeToolPurpose(String toolName) {
		return switch (toolName) {
			case "javax-to-jakarta" -> "  Migrates javax.* imports to jakarta.* namespace\n";
			case "pom-upgrader" -> "  Upgrades POM parent version and dependency coordinates\n";
			case "thymeleaf-migrator" ->
				"  Migrates Thymeleaf templates: fragment ~{} wrapping (with P0-safe variable expression skip), "
						+ "th:include→th:insert, xmlns:sec namespace update, WebJars version insertion\n";
			case "junit-migrator" -> "  Migrates JUnit 4 tests to JUnit 5\n";
			case "annotation-migrator" -> "  Migrates deprecated Spring annotations\n";
			case "property-migrator" -> "  Migrates application.properties keys for Spring Boot 3\n";
			default -> "  (purpose not documented)\n";
		};
	}

	List<String> extractKbFilesRead(List<PhaseCapture> phases, @Nullable Path knowledgeDir) {
		if (knowledgeDir == null) {
			return List.of();
		}
		String kbPrefix = knowledgeDir.toString();
		Set<String> kbFiles = new LinkedHashSet<>();
		for (PhaseCapture phase : phases) {
			if (!phase.hasToolUses()) {
				continue;
			}
			for (ToolUseRecord toolUse : phase.toolUses()) {
				if ("Read".equals(toolUse.name()) || "View".equals(toolUse.name())) {
					Object filePathObj = toolUse.input().get("file_path");
					if (filePathObj instanceof String filePath && filePath.startsWith(kbPrefix)) {
						// Store as relative path to KB root
						String relative = filePath.substring(kbPrefix.length());
						if (relative.startsWith("/")) {
							relative = relative.substring(1);
						}
						kbFiles.add(relative);
					}
				}
			}
		}
		return List.copyOf(kbFiles);
	}

	List<String> extractToolRecommendations(String roadmapMarkdown) {
		List<String> tools = new ArrayList<>();
		Matcher matcher = TOOL_NAME_PATTERN.matcher(roadmapMarkdown);
		while (matcher.find()) {
			String tool = matcher.group(1);
			if (!tools.contains(tool)) {
				tools.add(tool);
			}
		}
		return tools;
	}

	static String resolveModelId(String name) {
		return switch (name.toLowerCase()) {
			case "sonnet" -> CLIOptions.MODEL_SONNET;
			case "haiku" -> CLIOptions.MODEL_HAIKU;
			case "opus" -> CLIOptions.MODEL_OPUS;
			default -> name;
		};
	}

}
