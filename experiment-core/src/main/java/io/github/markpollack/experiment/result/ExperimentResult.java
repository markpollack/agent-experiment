package io.github.markpollack.experiment.result;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import io.github.markpollack.experiment.recording.RuntimeIdentity;
import io.github.markpollack.experiment.recording.MeasurementSummary;
import io.github.markpollack.experiment.judge.JudgeRunDeclaration;

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
 * @param counts what the run observed, counted: passes, non-passes, excluded items,
 * instrument failures and items never judged. No rate is stored; a rate is a derivation
 * and belongs to the reader, which is why {@link ItemCounts#passRate()} returns nothing
 * when the jury scored nothing. Null means a run recorded before counts existed
 * @param totalCostUsd sum of cost across all items
 * @param totalTokens sum of tokens across all items
 * @param totalDurationMs wall-clock duration of entire experiment
 * @param codeVersion git commit SHA of the experiment project at run time (null if not in
 * a git repo)
 * @param codeDirty whether the experiment project had uncommitted changes at run time
 * @param conditions the conditions the run executed under
 * @param instrument the jury this run was scored with, described before any vote (null
 * when not recorded)
 */
public record ExperimentResult(String experimentId, String experimentName, @Nullable String datasetVersion,
		boolean datasetDirty, @Nullable String datasetSemanticVersion, @Nullable KnowledgeManifest knowledgeManifest,
		Instant timestamp, List<ItemResult> items, Map<String, String> metadata, Map<String, Double> aggregateScores,
		@Nullable ItemCounts counts, @Nullable Double totalCostUsd, @Nullable Long totalTokens,
		@Nullable Long totalDurationMs, @Nullable String codeVersion, boolean codeDirty, RunConditions conditions,
		@Nullable InstrumentRecord instrument, @Nullable Integer recordingSchemaVersion,
		@Nullable String experimentKind, @Nullable RuntimeIdentity writer, @Nullable RuntimeIdentity judgeRuntime,
		@Nullable JudgeRunDeclaration judgeRun, @Nullable String runState, @Nullable MeasurementSummary measurements) {

	public ExperimentResult {
		java.util.Objects.requireNonNull(experimentId, "experimentId must not be null");
		java.util.Objects.requireNonNull(experimentName, "experimentName must not be null");
		java.util.Objects.requireNonNull(timestamp, "timestamp must not be null");
		items = List.copyOf(items);
		metadata = Map.copyOf(metadata);
		aggregateScores = Map.copyOf(aggregateScores);
		conditions = conditions == null ? RunConditions.undeclared() : conditions;
	}

	/** Number of declared Judge observations, absent for historical and agent runs. */
	@com.fasterxml.jackson.annotation.JsonProperty
	public @Nullable Integer declaredRows() {
		return judgeRun == null ? null : judgeRun.rows().size();
	}

	/** Rows entered, including input construction failures; not just completed calls. */
	@com.fasterxml.jackson.annotation.JsonProperty
	public @Nullable Integer attemptedRows() {
		return judgeRun == null ? null : (int) items.stream()
			.filter(v -> v.executionDetail() instanceof io.github.markpollack.experiment.judge.JudgeExecutionDetail d
					&& !"NOT_ATTEMPTED".equals(d.rowState()))
			.count();
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

		private RunConditions conditions = RunConditions.undeclared();

		private @Nullable InstrumentRecord instrument;

		private @Nullable ItemCounts counts;

		private @Nullable Double totalCostUsd;

		private @Nullable Long totalTokens;

		private @Nullable Long totalDurationMs;

		private @Nullable String codeVersion;

		private boolean codeDirty;

		private @Nullable Integer recordingSchemaVersion = 3;

		private String experimentKind = "AGENT";

		private @Nullable RuntimeIdentity writer;

		private @Nullable RuntimeIdentity judgeRuntime;

		private @Nullable JudgeRunDeclaration judgeRun;

		private String runState = "COMPLETE";

		private @Nullable MeasurementSummary measurements;

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

		public Builder datasetSemanticVersion(@Nullable String datasetSemanticVersion) {
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

		/**
		 * The conditions this run was executed under. Defaults to
		 * {@link RunConditions#undeclared(String...)} — a run that declares nothing is
		 * marked incomplete and will not be compared, rather than silently looking like a
		 * run with no conditions.
		 */
		public Builder conditions(RunConditions conditions) {
			this.conditions = conditions;
			return this;
		}

		/**
		 * The jury this run was scored with. Defaults to null, meaning not recorded —
		 * never to an empty description, which would pass for a jury with no judges.
		 */
		public Builder instrument(@Nullable InstrumentRecord instrument) {
			this.instrument = instrument;
			return this;
		}

		public Builder aggregateScores(Map<String, Double> aggregateScores) {
			this.aggregateScores = aggregateScores;
			return this;
		}

		/**
		 * What the run observed, counted. Defaults to null, meaning not recorded — a run
		 * from before counts existed, never a run that counted nothing.
		 */
		public Builder counts(@Nullable ItemCounts counts) {
			this.counts = counts;
			return this;
		}

		public Builder totalCostUsd(@Nullable Double totalCostUsd) {
			this.totalCostUsd = totalCostUsd;
			return this;
		}

		public Builder totalTokens(@Nullable Long totalTokens) {
			this.totalTokens = totalTokens;
			return this;
		}

		public Builder totalDurationMs(@Nullable Long totalDurationMs) {
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

		public Builder totalCostUsd(double value) {
			return totalCostUsd(Double.valueOf(value));
		}

		public Builder totalTokens(long value) {
			return totalTokens(Long.valueOf(value));
		}

		public Builder totalDurationMs(long value) {
			return totalDurationMs(Long.valueOf(value));
		}

		public Builder recordingSchemaVersion(@Nullable Integer value) {
			recordingSchemaVersion = value;
			return this;
		}

		public Builder experimentKind(String value) {
			experimentKind = value;
			return this;
		}

		public Builder writer(RuntimeIdentity value) {
			writer = value;
			return this;
		}

		public Builder judgeRuntime(RuntimeIdentity value) {
			judgeRuntime = value;
			return this;
		}

		public Builder judgeRun(JudgeRunDeclaration value) {
			judgeRun = value;
			return this;
		}

		public Builder runState(String value) {
			runState = value;
			return this;
		}

		public Builder measurements(MeasurementSummary value) {
			measurements = value;
			return this;
		}

		public ExperimentResult build() {
			if (recordingSchemaVersion != null) {
				if (writer == null)
					writer = RuntimeIdentity.writer();
				if (judgeRuntime == null)
					judgeRuntime = RuntimeIdentity.judge();
			}
			return new ExperimentResult(experimentId, experimentName, datasetVersion, datasetDirty,
					datasetSemanticVersion, knowledgeManifest, timestamp, items, metadata, aggregateScores, counts,
					totalCostUsd, totalTokens, totalDurationMs, codeVersion, codeDirty, conditions, instrument,
					recordingSchemaVersion, experimentKind, writer, judgeRuntime, judgeRun, runState, measurements);
		}

	}

}
