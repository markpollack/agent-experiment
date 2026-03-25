package io.github.markpollack.experiment.result;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Complete result of an experiment run.
 *
 * @param experimentId unique UUID per run
 * @param experimentName from config
 * @param datasetVersion git commit SHA at experiment time (nullable — null if dataset not
 * in git)
 * @param datasetDirty whether the dataset had uncommitted changes at experiment time
 * @param datasetSemanticVersion declared semantic version from dataset.json
 * @param knowledgeManifest snapshot of knowledge base state (nullable if no KB)
 * @param timestamp when the experiment ran
 * @param items per-item results
 * @param metadata run-level metadata
 * @param aggregateScores aggregated scores across items (mean per judge)
 * @param passRate fraction of items where verdict passed
 * @param totalCostUsd sum of cost across all items
 * @param totalTokens sum of tokens across all items
 * @param totalDurationMs wall-clock duration of entire experiment
 * @param codeVersion git commit SHA of the experiment project at run time (null if not in
 * a git repo)
 * @param codeDirty whether the experiment project had uncommitted changes at run time
 */
public record ExperimentResult(String experimentId, String experimentName, @Nullable String datasetVersion,
		boolean datasetDirty, String datasetSemanticVersion, @Nullable KnowledgeManifest knowledgeManifest,
		Instant timestamp, List<ItemResult> items, Map<String, String> metadata, Map<String, Double> aggregateScores,
		double passRate, double totalCostUsd, int totalTokens, long totalDurationMs, @Nullable String codeVersion,
		boolean codeDirty) {

	public ExperimentResult {
		java.util.Objects.requireNonNull(experimentId, "experimentId must not be null");
		java.util.Objects.requireNonNull(experimentName, "experimentName must not be null");
		java.util.Objects.requireNonNull(datasetSemanticVersion, "datasetSemanticVersion must not be null");
		java.util.Objects.requireNonNull(timestamp, "timestamp must not be null");
		items = List.copyOf(items);
		metadata = Map.copyOf(metadata);
		aggregateScores = Map.copyOf(aggregateScores);
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private String experimentId;

		private String experimentName;

		private @Nullable String datasetVersion;

		private boolean datasetDirty;

		private String datasetSemanticVersion;

		private @Nullable KnowledgeManifest knowledgeManifest;

		private Instant timestamp;

		private List<ItemResult> items = List.of();

		private Map<String, String> metadata = Map.of();

		private Map<String, Double> aggregateScores = Map.of();

		private double passRate;

		private double totalCostUsd;

		private int totalTokens;

		private long totalDurationMs;

		private @Nullable String codeVersion;

		private boolean codeDirty;

		private Builder() {
		}

		public Builder experimentId(String experimentId) {
			this.experimentId = experimentId;
			return this;
		}

		public Builder experimentName(String experimentName) {
			this.experimentName = experimentName;
			return this;
		}

		public Builder datasetVersion(@Nullable String datasetVersion) {
			this.datasetVersion = datasetVersion;
			return this;
		}

		public Builder datasetDirty(boolean datasetDirty) {
			this.datasetDirty = datasetDirty;
			return this;
		}

		public Builder datasetSemanticVersion(String datasetSemanticVersion) {
			this.datasetSemanticVersion = datasetSemanticVersion;
			return this;
		}

		public Builder knowledgeManifest(@Nullable KnowledgeManifest knowledgeManifest) {
			this.knowledgeManifest = knowledgeManifest;
			return this;
		}

		public Builder timestamp(Instant timestamp) {
			this.timestamp = timestamp;
			return this;
		}

		public Builder items(List<ItemResult> items) {
			this.items = items;
			return this;
		}

		public Builder metadata(Map<String, String> metadata) {
			this.metadata = metadata;
			return this;
		}

		public Builder aggregateScores(Map<String, Double> aggregateScores) {
			this.aggregateScores = aggregateScores;
			return this;
		}

		public Builder passRate(double passRate) {
			this.passRate = passRate;
			return this;
		}

		public Builder totalCostUsd(double totalCostUsd) {
			this.totalCostUsd = totalCostUsd;
			return this;
		}

		public Builder totalTokens(int totalTokens) {
			this.totalTokens = totalTokens;
			return this;
		}

		public Builder totalDurationMs(long totalDurationMs) {
			this.totalDurationMs = totalDurationMs;
			return this;
		}

		public Builder codeVersion(@Nullable String codeVersion) {
			this.codeVersion = codeVersion;
			return this;
		}

		public Builder codeDirty(boolean codeDirty) {
			this.codeDirty = codeDirty;
			return this;
		}

		public ExperimentResult build() {
			return new ExperimentResult(experimentId, experimentName, datasetVersion, datasetDirty,
					datasetSemanticVersion, knowledgeManifest, timestamp, items, metadata, aggregateScores, passRate,
					totalCostUsd, totalTokens, totalDurationMs, codeVersion, codeDirty);
		}

	}

}
