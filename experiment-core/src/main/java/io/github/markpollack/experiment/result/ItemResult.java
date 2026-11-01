package io.github.markpollack.experiment.result;

import java.nio.file.Path;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.Nullable;
import io.github.markpollack.experiment.recording.InterpretationSnapshot;
import io.github.markpollack.experiment.recording.RuntimeIdentity;

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
public record ItemResult(String itemId, String itemSlug, boolean success, @Nullable Boolean passed,
		@Nullable Double costUsd, @Nullable Long totalTokens, @Nullable Long durationMs, Map<String, Double> scores,
		Map<String, Object> metrics, @Nullable ExecutionDetail executionDetail, @Nullable RecordedVerdict verdict,
		@Nullable JsonNode interpretation, Map<String, Object> metadata, @Nullable Path workspacePath,
		@Nullable String verdictRole, @Nullable InterpretationSnapshot interpretationSnapshot, boolean historical) {

	public ItemResult {
		java.util.Objects.requireNonNull(itemId, "itemId must not be null");
		java.util.Objects.requireNonNull(itemSlug, "itemSlug must not be null");
		scores = Map.copyOf(scores);
		metrics = Map.copyOf(metrics);
		metadata = Map.copyOf(metadata);
		interpretation = interpretation == null ? null : interpretation.deepCopy();
	}

	@Override
	public @Nullable JsonNode interpretation() {
		return interpretation == null ? null : interpretation.deepCopy();
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
			.workspacePath(workspacePath)
			.verdictRole(verdictRole)
			.interpretationSnapshot(interpretationSnapshot)
			.historical(historical);
	}

	public static final class Builder {

		private String itemId;

		private String itemSlug;

		private boolean success;

		private @Nullable Boolean passed;

		private boolean passedSupplied;

		private @Nullable Double costUsd;

		private @Nullable Long totalTokens;

		private @Nullable Long durationMs;

		private Map<String, Double> scores = Map.of();

		private Map<String, Object> metrics = Map.of();

		private @Nullable ExecutionDetail executionDetail;

		private @Nullable RecordedVerdict verdict;

		private @Nullable JsonNode interpretation;

		private Map<String, Object> metadata = Map.of();

		private @Nullable Path workspacePath;

		private @Nullable String verdictRole;

		private @Nullable InterpretationSnapshot interpretationSnapshot;

		private boolean historical;

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
			this.passedSupplied = true;
			return this;
		}

		public Builder costUsd(@Nullable Double costUsd) {
			this.costUsd = costUsd;
			return this;
		}

		public Builder totalTokens(@Nullable Long totalTokens) {
			this.totalTokens = totalTokens;
			return this;
		}

		public Builder durationMs(@Nullable Long durationMs) {
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
			this.interpretationSnapshot = null;
			return this;
		}

		/** Record a live jury verdict using the stable Agent Experiment projection. */
		public Builder verdict(io.github.markpollack.judge.verdict.Verdict verdict) {
			return verdict(verdict, null);
		}

		/**
		 * Record a live jury verdict, and with it the library's reading of that verdict.
		 * <p>
		 * Taken here, where the live verdict still exists, rather than parsed back out of
		 * the stored projection afterwards.
		 */
		public Builder verdict(io.github.markpollack.judge.verdict.Verdict verdict, @Nullable String instrumentHash) {
			this.verdict = RecordedVerdict.from(verdict, instrumentHash);
			this.interpretation = InterpretationSnapshot.currentReading(this.verdict.document());
			return this;
		}

		/**
		 * The library's reading of this item's verdict. Set automatically when a live
		 * verdict is recorded.
		 */
		public Builder interpretation(@Nullable JsonNode interpretation) {
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

		public Builder costUsd(double value) {
			return costUsd(Double.valueOf(value));
		}

		public Builder totalTokens(long value) {
			return totalTokens(Long.valueOf(value));
		}

		public Builder durationMs(long value) {
			return durationMs(Long.valueOf(value));
		}

		public Builder verdictRole(@Nullable String value) {
			this.verdictRole = value;
			return this;
		}

		public Builder interpretationSnapshot(@Nullable InterpretationSnapshot value) {
			this.interpretationSnapshot = value;
			return this;
		}

		public Builder historical(boolean value) {
			this.historical = value;
			return this;
		}

		public ItemResult build() {
			if (verdict != null && historical && interpretationSnapshot == null)
				interpretationSnapshot = new InterpretationSnapshot("LEGACY_UNVERIFIED", verdict.sha256(), null, null,
						interpretation == null ? com.fasterxml.jackson.databind.node.NullNode.instance
								: interpretation);
			if (verdict != null && !historical && interpretationSnapshot == null) {
				interpretationSnapshot = InterpretationSnapshot.capture(verdict.document(), RuntimeIdentity.judge());
				if (interpretation == null)
					interpretation = interpretationSnapshot.reading();
			}
			if (verdict != null && verdictRole == null && !historical)
				verdictRole = "SUBJECT";
			if (!historical && verdict != null && !passedSupplied)
				passed = io.github.markpollack.experiment.attestation.ItemAccounting.passedFlagCurrent(interpretation);
			return new ItemResult(itemId, itemSlug, success, passed, costUsd, totalTokens, durationMs, scores, metrics,
					executionDetail, verdict, interpretation, metadata, workspacePath, verdictRole,
					interpretationSnapshot, historical);
		}

	}

}
