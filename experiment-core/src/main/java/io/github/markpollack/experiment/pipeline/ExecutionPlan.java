package io.github.markpollack.experiment.pipeline;

import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Generated execution plan — the primary output of the planning phase. Contains a
 * Forge-style roadmap and planning metrics.
 *
 * @param roadmapMarkdown Forge-style roadmap text (the primary artifact embedded in the
 * executor prompt)
 * @param toolRecommendations tool names referenced in the roadmap (e.g.,
 * "javax-to-jakarta", "pom-upgrader")
 * @param kbFilesRead knowledge base files read during planning (relative paths to KB
 * root, empty if no KB or no reads detected)
 * @param planningCostUsd cost of the planning Claude session
 * @param planningInputTokens input tokens consumed during planning
 * @param planningOutputTokens output tokens produced during planning
 * @param planningThinkingTokens thinking tokens consumed during planning
 * @param planningDurationMs wall-clock duration of planning phase in milliseconds
 * @param planningSessionId Claude session ID for the planning phase (nullable)
 */
public record ExecutionPlan(String roadmapMarkdown, List<String> toolRecommendations, List<String> kbFilesRead,
		double planningCostUsd, int planningInputTokens, int planningOutputTokens, int planningThinkingTokens,
		long planningDurationMs, @Nullable String planningSessionId) {

	public ExecutionPlan {
		Objects.requireNonNull(roadmapMarkdown, "roadmapMarkdown must not be null");
		toolRecommendations = toolRecommendations != null ? List.copyOf(toolRecommendations) : List.of();
		kbFilesRead = kbFilesRead != null ? List.copyOf(kbFilesRead) : List.of();
	}

	/** Total planning tokens across all categories. */
	public int planningTotalTokens() {
		return planningInputTokens + planningOutputTokens + planningThinkingTokens;
	}

}
