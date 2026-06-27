package io.github.markpollack.experiment.runner;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import io.github.markpollack.experiment.dataset.ItemFilter;
import io.github.markpollack.experiment.diagnostic.EfficiencyConfig;
import org.jspecify.annotations.Nullable;

/**
 * Configuration for a single experiment run.
 *
 * @param experimentName human-readable experiment name
 * @param datasetDir path to dataset directory containing dataset.json
 * @param itemFilter optional filter for selecting dataset items (bucket, tags, etc.)
 * @param model LLM model identifier (e.g., "sonnet", "opus")
 * @param promptTemplate template for constructing per-item prompts
 * @param perItemTimeout timeout per item invocation
 * @param systemPrompt optional system prompt appended to agent's base prompt
 * @param knowledgeBaseDir optional path to knowledge base root for ablation tracking
 * @param experimentTimeout optional timeout for entire experiment
 * @param metadata arbitrary metadata (model version, config hash, etc.)
 * @param baselineId optional explicit baseline experiment ID for comparison
 * @param preserveWorkspaces whether to preserve post-agent workspaces for forensic
 * inspection (null = true)
 * @param outputDir directory for experiment artifacts (preserved workspaces); when null,
 * workspaces are not preserved even if preserveWorkspaces is true
 * @param efficiencyConfig optional efficiency evaluation configuration; when null,
 * efficiency evaluation is skipped (backward compatible)
 * @param projectRoot root directory of the experiment project for git versioning; when
 * null, defaults to the JVM working directory at run time
 * @param requireCleanGit when true, throw if the experiment project has uncommitted
 * changes; when false (default), log a warning and continue
 * @param journalEnabled whether to write a canonical agent-journal trace (events +
 * per-step cost) per item; null defaults to true. Requires {@code outputDir} (the durable
 * journal is written beside the experiment artifacts); a no-op without one. Disable via
 * {@code withoutJournal()} for lightweight/judge runs.
 */
public record ExperimentConfig(String experimentName, Path datasetDir, @Nullable ItemFilter itemFilter, String model,
		String promptTemplate, Duration perItemTimeout, @Nullable String systemPrompt, @Nullable Path knowledgeBaseDir,
		@Nullable Duration experimentTimeout, Map<String, String> metadata, @Nullable String baselineId,
		@Nullable Boolean preserveWorkspaces, @Nullable Path outputDir, @Nullable EfficiencyConfig efficiencyConfig,
		@Nullable Path projectRoot, boolean requireCleanGit, @Nullable Boolean journalEnabled) {

	public ExperimentConfig {
		java.util.Objects.requireNonNull(experimentName, "experimentName must not be null");
		java.util.Objects.requireNonNull(datasetDir, "datasetDir must not be null");
		java.util.Objects.requireNonNull(model, "model must not be null");
		java.util.Objects.requireNonNull(promptTemplate, "promptTemplate must not be null");
		java.util.Objects.requireNonNull(perItemTimeout, "perItemTimeout must not be null");
		metadata = Map.copyOf(metadata);
	}

	/**
	 * Whether workspaces should be preserved after agent invocation. Defaults to true
	 * when {@code preserveWorkspaces} is null and {@code outputDir} is set.
	 */
	public boolean shouldPreserveWorkspaces() {
		return (preserveWorkspaces == null || preserveWorkspaces) && outputDir != null;
	}

	/**
	 * Whether per-item journaling is requested. Defaults to true when
	 * {@code journalEnabled} is null. A durable journal is only actually written when
	 * {@code outputDir} is also set.
	 */
	public boolean shouldJournal() {
		return journalEnabled == null || journalEnabled;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private String experimentName;

		private Path datasetDir;

		private @Nullable ItemFilter itemFilter;

		private String model;

		private String promptTemplate;

		private Duration perItemTimeout;

		private @Nullable String systemPrompt;

		private @Nullable Path knowledgeBaseDir;

		private @Nullable Duration experimentTimeout;

		private Map<String, String> metadata = Map.of();

		private @Nullable String baselineId;

		private @Nullable Boolean preserveWorkspaces;

		private @Nullable Path outputDir;

		private @Nullable EfficiencyConfig efficiencyConfig;

		private @Nullable Path projectRoot;

		private boolean requireCleanGit;

		private @Nullable Boolean journalEnabled;

		private Builder() {
		}

		public Builder experimentName(String experimentName) {
			this.experimentName = experimentName;
			return this;
		}

		public Builder datasetDir(Path datasetDir) {
			this.datasetDir = datasetDir;
			return this;
		}

		public Builder itemFilter(@Nullable ItemFilter itemFilter) {
			this.itemFilter = itemFilter;
			return this;
		}

		public Builder model(String model) {
			this.model = model;
			return this;
		}

		public Builder promptTemplate(String promptTemplate) {
			this.promptTemplate = promptTemplate;
			return this;
		}

		public Builder perItemTimeout(Duration perItemTimeout) {
			this.perItemTimeout = perItemTimeout;
			return this;
		}

		public Builder systemPrompt(@Nullable String systemPrompt) {
			this.systemPrompt = systemPrompt;
			return this;
		}

		public Builder knowledgeBaseDir(@Nullable Path knowledgeBaseDir) {
			this.knowledgeBaseDir = knowledgeBaseDir;
			return this;
		}

		public Builder experimentTimeout(@Nullable Duration experimentTimeout) {
			this.experimentTimeout = experimentTimeout;
			return this;
		}

		public Builder metadata(Map<String, String> metadata) {
			this.metadata = metadata;
			return this;
		}

		public Builder baselineId(@Nullable String baselineId) {
			this.baselineId = baselineId;
			return this;
		}

		public Builder preserveWorkspaces(@Nullable Boolean preserveWorkspaces) {
			this.preserveWorkspaces = preserveWorkspaces;
			return this;
		}

		public Builder outputDir(@Nullable Path outputDir) {
			this.outputDir = outputDir;
			return this;
		}

		public Builder efficiencyConfig(@Nullable EfficiencyConfig efficiencyConfig) {
			this.efficiencyConfig = efficiencyConfig;
			return this;
		}

		public Builder projectRoot(@Nullable Path projectRoot) {
			this.projectRoot = projectRoot;
			return this;
		}

		public Builder requireCleanGit(boolean requireCleanGit) {
			this.requireCleanGit = requireCleanGit;
			return this;
		}

		/**
		 * Whether to write a canonical per-item journal (events + per-step cost). Null
		 * (the default) means enabled when an {@code outputDir} is configured.
		 */
		public Builder journalEnabled(@Nullable Boolean journalEnabled) {
			this.journalEnabled = journalEnabled;
			return this;
		}

		/**
		 * Disables per-item journaling — the clean opt-out for lightweight/judge runs.
		 */
		public Builder withoutJournal() {
			this.journalEnabled = false;
			return this;
		}

		public ExperimentConfig build() {
			return new ExperimentConfig(experimentName, datasetDir, itemFilter, model, promptTemplate, perItemTimeout,
					systemPrompt, knowledgeBaseDir, experimentTimeout, metadata, baselineId, preserveWorkspaces,
					outputDir, efficiencyConfig, projectRoot, requireCleanGit, journalEnabled);
		}

	}

}
