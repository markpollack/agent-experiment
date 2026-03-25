package io.github.markpollack.experiment.agent;

import java.util.List;
import java.util.Map;

import io.github.markpollack.experiment.pipeline.AnalysisEnvelope;
import io.github.markpollack.experiment.pipeline.ExecutionPlan;
import io.github.markpollack.journal.claude.PhaseCapture;
import org.jspecify.annotations.Nullable;

/**
 * Result of an agent invocation on a single dataset item.
 *
 * @param success whether the agent completed without error/timeout
 * @param status terminal status of the invocation
 * @param phases per-phase captures from the agent
 * @param inputTokens aggregate non-cached input tokens across phases
 * @param outputTokens aggregate output tokens across phases
 * @param thinkingTokens aggregate thinking tokens across phases
 * @param cacheCreationInputTokens aggregate cache-write tokens across phases
 * @param cacheReadInputTokens aggregate cache-read tokens across phases
 * @param totalCostUsd aggregate cost in USD across phases
 * @param durationMs wall-clock duration in milliseconds
 * @param sessionId agent session ID (nullable)
 * @param metadata pass-through metadata from context
 * @param errorMessage error detail if status is ERROR/TIMEOUT
 * @param analysis pipeline analysis envelope (nullable — only set by
 * PipelineAgentInvoker)
 * @param executionPlan pipeline execution plan (nullable — only set by
 * PipelineAgentInvoker)
 */
public record InvocationResult(boolean success, TerminalStatus status, List<PhaseCapture> phases, int inputTokens,
		int outputTokens, int thinkingTokens, int cacheCreationInputTokens, int cacheReadInputTokens,
		double totalCostUsd, long durationMs, @Nullable String sessionId, Map<String, String> metadata,
		@Nullable String errorMessage, @Nullable AnalysisEnvelope analysis, @Nullable ExecutionPlan executionPlan) {

	public InvocationResult {
		java.util.Objects.requireNonNull(status, "status must not be null");
		phases = List.copyOf(phases);
		metadata = Map.copyOf(metadata);
	}

	/** Total input tokens including prompt cache reads and cache creation. */
	public int totalInputTokens() {
		return inputTokens + cacheCreationInputTokens + cacheReadInputTokens;
	}

	/** Total tokens across all categories. */
	public int totalTokens() {
		return totalInputTokens() + outputTokens + thinkingTokens;
	}

	/** Factory for a successful completion. */
	public static InvocationResult completed(List<PhaseCapture> phases, int inputTokens, int outputTokens,
			int thinkingTokens, double totalCostUsd, long durationMs, @Nullable String sessionId,
			Map<String, String> metadata) {
		return new InvocationResult(true, TerminalStatus.COMPLETED, phases, inputTokens, outputTokens, thinkingTokens,
				0, 0, totalCostUsd, durationMs, sessionId, metadata, null, null, null);
	}

	/** Factory for a timeout. */
	public static InvocationResult timeout(long durationMs, Map<String, String> metadata,
			@Nullable String errorMessage) {
		return new InvocationResult(false, TerminalStatus.TIMEOUT, List.of(), 0, 0, 0, 0, 0, 0.0, durationMs, null,
				metadata, errorMessage, null, null);
	}

	/** Factory for an error. */
	public static InvocationResult error(String errorMessage, Map<String, String> metadata) {
		return new InvocationResult(false, TerminalStatus.ERROR, List.of(), 0, 0, 0, 0, 0, 0.0, 0, null, metadata,
				errorMessage, null, null);
	}

	/**
	 * Factory that aggregates token counts and cost from a list of PhaseCapture records.
	 * @param phases per-phase captures from the agent
	 * @param durationMs wall-clock duration in milliseconds
	 * @param sessionId agent session ID (nullable, extracted from last phase if null)
	 * @param metadata pass-through metadata
	 * @return a completed InvocationResult with aggregated metrics
	 */
	public static InvocationResult fromPhases(List<PhaseCapture> phases, long durationMs, @Nullable String sessionId,
			Map<String, String> metadata) {
		int totalInput = phases.stream().mapToInt(PhaseCapture::inputTokens).sum();
		int totalOutput = phases.stream().mapToInt(PhaseCapture::outputTokens).sum();
		int totalThinking = phases.stream().mapToInt(PhaseCapture::thinkingTokens).sum();
		int totalCacheCreation = phases.stream().mapToInt(PhaseCapture::cacheCreationInputTokens).sum();
		int totalCacheRead = phases.stream().mapToInt(PhaseCapture::cacheReadInputTokens).sum();
		double totalCost = phases.stream().mapToDouble(PhaseCapture::totalCostUsd).sum();

		// Use provided sessionId, or fall back to last phase's sessionId
		String resolvedSessionId = sessionId;
		if (resolvedSessionId == null && !phases.isEmpty()) {
			resolvedSessionId = phases.get(phases.size() - 1).sessionId();
		}

		// Check if any phase reported an error
		boolean hasError = phases.stream().anyMatch(PhaseCapture::isError);

		if (hasError) {
			return new InvocationResult(false, TerminalStatus.ERROR, phases, totalInput, totalOutput, totalThinking,
					totalCacheCreation, totalCacheRead, totalCost, durationMs, resolvedSessionId, metadata,
					"Agent reported error", null, null);
		}

		return new InvocationResult(true, TerminalStatus.COMPLETED, phases, totalInput, totalOutput, totalThinking,
				totalCacheCreation, totalCacheRead, totalCost, durationMs, resolvedSessionId, metadata, null, null,
				null);
	}

}
