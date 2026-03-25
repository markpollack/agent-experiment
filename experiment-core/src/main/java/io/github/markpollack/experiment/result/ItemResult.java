package io.github.markpollack.experiment.result;

import java.nio.file.Path;
import java.util.Map;

import io.github.markpollack.experiment.agent.InvocationResult;
import org.jspecify.annotations.Nullable;
import org.springaicommunity.judge.jury.Verdict;

/**
 * Result of evaluating a single dataset item within an experiment.
 *
 * @param itemId stable fixture ID (matches across runs)
 * @param itemSlug human-readable fixture slug
 * @param success whether agent invocation succeeded
 * @param passed derived convenience: verdict pass/fail (false if invocation failed)
 * @param costUsd cost in USD for this item
 * @param totalTokens total tokens for this item
 * @param durationMs wall-clock duration for this item
 * @param scores per-judge normalized scores (0.0-1.0) for cross-run comparison
 * @param metrics additional operational metrics (input_tokens, output_tokens,
 * thinking_tokens, etc.)
 * @param invocationResult full agent invocation result
 * @param verdict full agent-judge-core Verdict (nullable if invocation failed before
 * judging)
 * @param metadata item-level metadata
 * @param workspacePath path to preserved workspace (null when not preserved)
 */
public record ItemResult(String itemId, String itemSlug, boolean success, boolean passed, double costUsd,
		int totalTokens, long durationMs, Map<String, Double> scores, Map<String, Object> metrics,
		@Nullable InvocationResult invocationResult, @Nullable Verdict verdict, Map<String, Object> metadata,
		@Nullable Path workspacePath) {

	public ItemResult {
		java.util.Objects.requireNonNull(itemId, "itemId must not be null");
		java.util.Objects.requireNonNull(itemSlug, "itemSlug must not be null");
		scores = Map.copyOf(scores);
		metrics = Map.copyOf(metrics);
		metadata = Map.copyOf(metadata);
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private String itemId;

		private String itemSlug;

		private boolean success;

		private boolean passed;

		private double costUsd;

		private int totalTokens;

		private long durationMs;

		private Map<String, Double> scores = Map.of();

		private Map<String, Object> metrics = Map.of();

		private @Nullable InvocationResult invocationResult;

		private @Nullable Verdict verdict;

		private Map<String, Object> metadata = Map.of();

		private @Nullable Path workspacePath;

		private Builder() {
		}

		public Builder itemId(String itemId) {
			this.itemId = itemId;
			return this;
		}

		public Builder itemSlug(String itemSlug) {
			this.itemSlug = itemSlug;
			return this;
		}

		public Builder success(boolean success) {
			this.success = success;
			return this;
		}

		public Builder passed(boolean passed) {
			this.passed = passed;
			return this;
		}

		public Builder costUsd(double costUsd) {
			this.costUsd = costUsd;
			return this;
		}

		public Builder totalTokens(int totalTokens) {
			this.totalTokens = totalTokens;
			return this;
		}

		public Builder durationMs(long durationMs) {
			this.durationMs = durationMs;
			return this;
		}

		public Builder scores(Map<String, Double> scores) {
			this.scores = scores;
			return this;
		}

		public Builder metrics(Map<String, Object> metrics) {
			this.metrics = metrics;
			return this;
		}

		public Builder invocationResult(@Nullable InvocationResult invocationResult) {
			this.invocationResult = invocationResult;
			return this;
		}

		public Builder verdict(@Nullable Verdict verdict) {
			this.verdict = verdict;
			return this;
		}

		public Builder metadata(Map<String, Object> metadata) {
			this.metadata = metadata;
			return this;
		}

		public Builder workspacePath(@Nullable Path workspacePath) {
			this.workspacePath = workspacePath;
			return this;
		}

		public ItemResult build() {
			return new ItemResult(itemId, itemSlug, success, passed, costUsd, totalTokens, durationMs, scores, metrics,
					invocationResult, verdict, metadata, workspacePath);
		}

	}

}
