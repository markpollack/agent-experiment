package io.github.markpollack.experiment.scoring.claude;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.markpollack.experiment.pipeline.ExecutionPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.claude.agent.sdk.ClaudeClient;
import org.springaicommunity.claude.agent.sdk.ClaudeSyncClient;
import org.springaicommunity.claude.agent.sdk.config.PermissionMode;
import org.springaicommunity.claude.agent.sdk.transport.CLIOptions;
import org.springaicommunity.claude.agent.sdk.types.Message;
import org.springaicommunity.claude.agent.sdk.types.ResultMessage;
import org.springaicommunity.judge.JudgeMetadata;
import org.springaicommunity.judge.JudgeType;
import org.springaicommunity.judge.JudgeWithMetadata;
import org.springaicommunity.judge.context.JudgmentContext;
import org.springaicommunity.judge.result.Check;
import org.springaicommunity.judge.result.Judgment;
import org.springaicommunity.judge.result.JudgmentStatus;
import org.springaicommunity.judge.score.NumericalScore;

/**
 * LLM-powered Tier 3 judge for the {@link org.springaicommunity.judge.jury.CascadedJury}.
 *
 * <p>
 * Extracts VERIFY checkpoints from the execution plan's roadmap and asks Claude to
 * evaluate each criterion against the workspace. Produces a {@link NumericalScore} (0–1)
 * representing the fraction of satisfied criteria, with per-criterion diagnostic metadata
 * via {@link Check} entries.
 *
 * <p>
 * Follows the {@code ClaudePlanGenerator} pattern for Claude SDK lifecycle management.
 */
public class SemanticDiffJudge implements JudgeWithMetadata {

	private static final Logger logger = LoggerFactory.getLogger(SemanticDiffJudge.class);

	private static final JudgeMetadata METADATA = new JudgeMetadata("semantic_diff",
			"LLM-powered semantic evaluation using plan-derived criteria", JudgeType.LLM_POWERED);

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	@SuppressWarnings("unchecked")
	private static final Map<String, Object> EVALUATION_SCHEMA = Map.of("type", "object", "properties",
			Map.of("result",
					Map.of("type", "string", "enum", List.of("PASS", "FAIL"), "description",
							"Whether the criterion is satisfied"),
					"reasoning", Map.of("type", "string", "description", "Brief explanation (1-2 sentences)")),
			"required", List.of("result", "reasoning"));

	private final SemanticDiffJudgeConfig config;

	public SemanticDiffJudge(SemanticDiffJudgeConfig config) {
		this.config = config;
	}

	@Override
	public JudgeMetadata metadata() {
		return METADATA;
	}

	@Override
	public Judgment judge(JudgmentContext context) {
		// Extract plan from metadata
		Object planObj = context.metadata().get("plan");
		if (!(planObj instanceof ExecutionPlan plan)) {
			return Judgment.abstain("No execution plan in context metadata");
		}

		// Extract workspace
		Path workspace = context.workspace();
		if (workspace == null) {
			return Judgment.abstain("No workspace path in context");
		}

		// Extract criteria from roadmap
		List<String> criteria = CriteriaExtractor.extract(plan.roadmapMarkdown());
		if (criteria.isEmpty()) {
			return Judgment.abstain("No VERIFY criteria found in execution plan roadmap");
		}

		// Cap at max
		if (criteria.size() > config.maxCriteriaToEvaluate()) {
			criteria = criteria.subList(0, config.maxCriteriaToEvaluate());
		}

		logger.info("Evaluating {} criteria against workspace {}", criteria.size(), workspace);

		// Evaluate each criterion
		List<CriterionResult> results = new ArrayList<>();
		int errorCount = 0;
		for (String criterion : criteria) {
			CriterionResult result = evaluateCriterion(criterion, workspace);
			results.add(result);
			if (result.confidence() == 0.0) {
				errorCount++;
			}
		}

		// If ALL criteria failed due to LLM errors, return ERROR judgment
		if (errorCount == results.size()) {
			return Judgment.error("All " + results.size() + " criterion evaluations failed due to LLM errors", null);
		}

		// Build checks and compute score
		List<Check> checks = new ArrayList<>();
		int passed = 0;
		for (CriterionResult result : results) {
			if (result.passed()) {
				checks.add(Check.pass(result.criterion(), result.reasoning()));
				passed++;
			}
			else {
				checks.add(Check.fail(result.criterion(), result.reasoning()));
			}
		}

		double scoreValue = (double) passed / results.size();
		NumericalScore score = NumericalScore.normalized(scoreValue);
		JudgmentStatus status = scoreValue >= 0.5 ? JudgmentStatus.PASS : JudgmentStatus.FAIL;

		String reasoning = String.format("%d/%d criteria passed (%.0f%%)", passed, results.size(), scoreValue * 100);
		logger.info("Semantic evaluation complete: {}", reasoning);

		return Judgment.builder()
			.score(score)
			.status(status)
			.reasoning(reasoning)
			.checks(checks)
			.metadata("criteriaTotal", results.size())
			.metadata("criteriaPassed", passed)
			.build();
	}

	CriterionResult evaluateCriterion(String criterion, Path workspace) {
		CLIOptions options = buildOptions();
		try (ClaudeSyncClient client = buildClient(options, workspace)) {
			String prompt = buildEvaluationPrompt(criterion);

			// Use connectAndReceive to access ResultMessage.structuredOutput
			// connectText() only returns AssistantMessage text (thinking/tool-use
			// narrative),
			// not the --json-schema structured output which lives in the ResultMessage
			String structuredJson = null;
			String textFallback = null;
			StringBuilder textBuilder = new StringBuilder();
			for (Message msg : client.connectAndReceive(prompt)) {
				if (msg instanceof ResultMessage rm && rm.hasStructuredOutput()) {
					Map<String, Object> output = rm.getStructuredOutputAsMap();
					if (output != null) {
						structuredJson = OBJECT_MAPPER.writeValueAsString(output);
					}
				}
				else if (msg instanceof org.springaicommunity.claude.agent.sdk.types.AssistantMessage am) {
					textBuilder.append(am.text());
				}
			}
			if (textBuilder.length() > 0) {
				textFallback = textBuilder.toString();
			}

			// Prefer structured output, fall back to text
			String response = structuredJson != null ? structuredJson : textFallback;
			return parseResponse(criterion, response);
		}
		catch (Exception ex) {
			logger.warn("LLM evaluation failed for criterion '{}': {}", criterion, ex.getMessage());
			return new CriterionResult(criterion, false, "LLM evaluation error: " + ex.getMessage(), 0.0);
		}
	}

	CLIOptions buildOptions() {
		String resolvedModel = resolveModelId(config.model());
		return CLIOptions.builder()
			.permissionMode(PermissionMode.DANGEROUSLY_SKIP_PERMISSIONS)
			.model(resolvedModel)
			.timeout(config.timeout())
			.jsonSchema(EVALUATION_SCHEMA)
			.build();
	}

	ClaudeSyncClient buildClient(CLIOptions options, Path workspace) {
		return ClaudeClient.sync(options).workingDirectory(workspace).build();
	}

	private String buildEvaluationPrompt(String criterion) {
		return """
				Evaluate whether this workspace satisfies the following criterion:

				%s

				Examine the relevant files in the workspace. \
				Return your evaluation as JSON with "result" (PASS or FAIL) and "reasoning" (1-2 sentences)."""
			.formatted(criterion);
	}

	private CriterionResult parseResponse(String criterion, String response) {
		if (response == null || response.isBlank()) {
			return new CriterionResult(criterion, false, "Empty response from LLM", 0.5);
		}

		// Try JSON parsing first (structured output via --json-schema)
		try {
			JsonNode node = OBJECT_MAPPER.readTree(response.strip());
			if (node.has("result") && node.has("reasoning")) {
				boolean passed = "PASS".equalsIgnoreCase(node.get("result").asText());
				String reasoning = node.get("reasoning").asText();
				return new CriterionResult(criterion, passed, reasoning, 1.0);
			}
		}
		catch (Exception ignored) {
			// Fall through to text parsing
		}

		// Fallback: text-based parsing
		String normalized = response.strip().toUpperCase();
		if (normalized.startsWith("PASS")) {
			return new CriterionResult(criterion, true, response.strip(), 1.0);
		}
		if (normalized.startsWith("FAIL")) {
			return new CriterionResult(criterion, false, response.strip(), 1.0);
		}

		// Search for keywords anywhere
		boolean containsPass = normalized.contains("PASS");
		boolean containsFail = normalized.contains("FAIL");
		if (containsPass && !containsFail) {
			return new CriterionResult(criterion, true, response.strip(), 0.5);
		}
		if (containsFail && !containsPass) {
			return new CriterionResult(criterion, false, response.strip(), 0.5);
		}

		return new CriterionResult(criterion, false, "Ambiguous LLM response: " + response.strip(), 0.5);
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
