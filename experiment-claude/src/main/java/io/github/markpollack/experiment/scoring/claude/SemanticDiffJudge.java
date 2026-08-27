package io.github.markpollack.experiment.scoring.claude;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.markpollack.agents.claude.ClaudeAgentModel;
import io.github.markpollack.agents.claude.ClaudeAgentOptions;
import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.model.AgentApi;
import io.github.markpollack.agents.model.AgentOptions;
import org.jspecify.annotations.Nullable;
import io.github.markpollack.judge.JudgeMetadata;
import io.github.markpollack.judge.JudgeType;
import io.github.markpollack.judge.JudgeWithMetadata;
import io.github.markpollack.judge.context.JudgmentContext;
import io.github.markpollack.judge.result.Check;
import io.github.markpollack.judge.result.Judgment;

/**
 * LLM-powered Tier 3 judge for the {@link io.github.markpollack.judge.jury.CascadedJury}.
 *
 * <p>
 * Extracts VERIFY checkpoints from the {@code plan} roadmap markdown carried in the
 * judgment context metadata and asks an agent to evaluate each criterion against the
 * workspace. Produces a normalized score (0–1) representing the fraction of satisfied
 * criteria, with per-criterion diagnostic metadata via {@link Check} entries.
 *
 * <p>
 * Runs through {@link AgentClient} rather than a vendor SDK. The default model is
 * {@link ClaudeAgentModel}; pass an {@link AgentApi} to drive a different provider, in
 * which case the caller owns its lifecycle.
 *
 * <h2>The judge does not inherit its subject's model</h2>
 *
 * <p>
 * The model comes from {@link SemanticDiffJudgeConfig}, never from the experiment under
 * test. An experiment varies its subject and holds everything else constant; a judge that
 * switched model with its subject would be a second variable, and a silent one — every
 * run would still complete and every score would still be produced. The model that
 * produced a score is therefore written into the judgment metadata as {@code judgeModel},
 * because a score is not interpretable without it.
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

	private final @Nullable AgentApi agentApi;

	public SemanticDiffJudge(SemanticDiffJudgeConfig config) {
		this(config, null);
	}

	/**
	 * @param config judging policy, including the model that is this instrument's
	 * identity
	 * @param agentApi the model to drive, or null for the {@link ClaudeAgentModel}
	 * default; when non-null the caller owns its lifecycle
	 */
	public SemanticDiffJudge(SemanticDiffJudgeConfig config, @Nullable AgentApi agentApi) {
		this.config = config;
		this.agentApi = agentApi;
	}

	@Override
	public JudgeMetadata metadata() {
		return METADATA;
	}

	@Override
	public Judgment judge(JudgmentContext context) {
		// Extract the roadmap markdown from metadata
		Object planObj = context.metadata().get("plan");
		if (!(planObj instanceof String roadmapMarkdown)) {
			return Judgment.abstain("No plan roadmap in context metadata");
		}

		// Extract workspace
		Path workspace = context.workspace();
		if (workspace == null) {
			return Judgment.abstain("No workspace path in context");
		}

		// Extract criteria from roadmap
		List<String> criteria = CriteriaExtractor.extract(roadmapMarkdown);
		if (criteria.isEmpty()) {
			return Judgment.abstain("No VERIFY criteria found in plan roadmap");
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
			return Judgment.error("All " + results.size() + " criterion evaluations failed due to LLM errors");
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
		String reasoning = String.format("%d/%d criteria passed (%.0f%%)", passed, results.size(), scoreValue * 100);
		logger.info("Semantic evaluation complete: {}", reasoning);

		return Judgment.scored(scoreValue)
			.passingAt(0.5)
			.reasoning(reasoning)
			.checks(checks)
			.metadata("criteriaTotal", results.size())
			.metadata("criteriaPassed", passed)
			.metadata("judgeModel", config.model())
			.build();
	}

	CriterionResult evaluateCriterion(String criterion, Path workspace) {
		try {
			String response = evaluate(buildEvaluationPrompt(criterion), workspace);
			return parseResponse(criterion, response);
		}
		catch (Exception ex) {
			logger.warn("LLM evaluation failed for criterion '{}': {}", criterion, ex.getMessage());
			return new CriterionResult(criterion, false, "LLM evaluation error: " + ex.getMessage(), 0.0);
		}
	}

	/**
	 * Runs one criterion prompt and returns the agent's primary output. Package-private
	 * for testability.
	 *
	 * <p>
	 * The structured output requested by {@link #EVALUATION_SCHEMA} arrives as that
	 * primary output: a provider asked for a schema answers with it. When a provider
	 * answers in prose instead, {@link #parseResponse} still reads it and records the
	 * lower confidence, so the difference stays visible rather than silent.
	 */
	String evaluate(String prompt, Path workspace) {
		AgentOptions options = buildOptions(workspace);

		if (this.agentApi != null) {
			return AgentClient.create(this.agentApi).run(prompt, options).getResult();
		}

		try (ClaudeAgentModel model = ClaudeAgentModel.builder()
			.workingDirectory(workspace)
			.timeout(config.timeout())
			.build()) {
			return AgentClient.create(model).run(prompt, options).getResult();
		}
	}

	/**
	 * Builds the provider options for one criterion evaluation. Package-private for
	 * testability. Model names pass through untranslated — the CLI resolves its own
	 * aliases, so no model table is pinned here.
	 */
	AgentOptions buildOptions(Path workspace) {
		return ClaudeAgentOptions.builder()
			.model(config.model())
			.timeout(config.timeout())
			.workingDirectory(workspace.toString())
			.yolo(true)
			.jsonSchema(EVALUATION_SCHEMA)
			.build();
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

}
