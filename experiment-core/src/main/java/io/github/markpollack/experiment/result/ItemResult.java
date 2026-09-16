package io.github.markpollack.experiment.result;

import java.nio.file.Path;
import java.util.Map;

import io.github.markpollack.judge.jury.interpretation.Interpretation;
import io.github.markpollack.judge.jury.interpretation.Verdicts;
import org.jspecify.annotations.Nullable;

/**
 * Result of evaluating a single dataset item within an experiment.
 *
 * @param itemId stable fixture ID (matches across runs)
 * @param itemSlug human-readable fixture slug
 * @param success whether agent invocation succeeded
 * @param passed whether the jury passed the subject, or <b>null when the jury decided
 * nothing about it</b>: the item was never judged, the criteria did not apply, or the
 * instrument could not score it. Null is not false. A run nobody judged and a run that
 * failed everything are different results, and writing false for both is what made them
 * render identically on a chart
 * @param costUsd cost in USD for this item
 * @param totalTokens total tokens for this item
 * @param durationMs wall-clock duration for this item
 * @param scores per-judge normalized scores (0.0-1.0) for cross-run comparison
 * @param metrics additional operational metrics (input_tokens, output_tokens,
 * thinking_tokens, etc.)
 * @param executionDetail domain-specific execution detail (e.g. InvocationResult for
 * agent experiments)
 * @param verdict Agent Experiment-owned recorded verdict (nullable if invocation failed
 * before judging)
 * @param interpretation what the verdict says, as the library that produced it reads it:
 * which stage decided, what it says about the subject, and what the record is missing.
 * Written here rather than derived by readers, so that no consumer has to re-derive a
 * meaning this project does not own. Null exactly when there is no verdict
 * @param metadata item-level metadata
 * @param workspacePath path to preserved workspace (null when not preserved)
 */
public record ItemResult(String itemId, String itemSlug, boolean success, @Nullable Boolean passed, double costUsd,
		int totalTokens, long durationMs, Map<String, Double> scores, Map<String, Object> metrics,
		@Nullable ExecutionDetail executionDetail, @Nullable RecordedVerdict verdict,
		@Nullable Interpretation interpretation, Map<String, Object> metadata, @Nullable Path workspacePath) {

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

	/**
	 * Create a builder pre-populated with this result's values for modification.
	 * @return a new builder initialized from this instance
	 */
	public Builder toBuilder() {
		return new Builder().itemId(itemId)
			.itemSlug(itemSlug)
			.success(success)
			.passed(passed)
			.costUsd(costUsd)
			.totalTokens(totalTokens)
			.durationMs(durationMs)
			.scores(scores)
			.metrics(metrics)
			.executionDetail(executionDetail)
			.verdict(verdict)
			.interpretation(interpretation)
			.metadata(metadata)
			.workspacePath(workspacePath);
	}

	public static final class Builder {

		private String itemId;

		private String itemSlug;

		private boolean success;

		private @Nullable Boolean passed;

		private double costUsd;

		private int totalTokens;

		private long durationMs;

		private Map<String, Double> scores = Map.of();

		private Map<String, Object> metrics = Map.of();

		private @Nullable ExecutionDetail executionDetail;

		private @Nullable RecordedVerdict verdict;

		private @Nullable Interpretation interpretation;

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

		/**
		 * Whether the jury passed the subject. Pass null when it decided nothing — never
		 * judged, not applicable, or unscorable — rather than false.
		 */
		public Builder passed(@Nullable Boolean passed) {
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

		public Builder executionDetail(@Nullable ExecutionDetail executionDetail) {
			this.executionDetail = executionDetail;
			return this;
		}

		public Builder verdict(@Nullable RecordedVerdict verdict) {
			this.verdict = verdict;
			return this;
		}

		/** Record a live jury verdict using the stable Agent Experiment projection. */
		public Builder verdict(io.github.markpollack.judge.jury.Verdict verdict) {
			return verdict(verdict, null);
		}

		/**
		 * Record a live jury verdict, and with it the library's reading of that verdict.
		 * <p>
		 * Taken here, where the live verdict still exists, rather than parsed back out of
		 * the stored projection afterwards.
		 */
		public Builder verdict(io.github.markpollack.judge.jury.Verdict verdict, @Nullable String instrumentHash) {
			this.verdict = RecordedVerdict.from(verdict, instrumentHash);
			this.interpretation = Verdicts.interpret(verdict);
			return this;
		}

		/**
		 * The library's reading of this item's verdict. Set automatically when a live
		 * verdict is recorded.
		 */
		public Builder interpretation(@Nullable Interpretation interpretation) {
			this.interpretation = interpretation;
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
					executionDetail, verdict, interpretation, metadata, workspacePath);
		}

	}

}
