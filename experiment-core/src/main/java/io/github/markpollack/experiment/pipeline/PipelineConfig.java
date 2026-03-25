package io.github.markpollack.experiment.pipeline;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Pipeline-level configuration. Separate from
 * {@link io.github.markpollack.experiment.runner.ExperimentConfig} (pipeline concerns vs.
 * experiment concerns).
 *
 * @param knowledgeDir path to knowledge store root directory (nullable — no knowledge
 * store)
 * @param toolPaths tool name to executable JAR path mapping
 * @param targetBootVersion target Spring Boot version (nullable)
 * @param targetJavaVersion target Java version (nullable)
 * @param planningModel model for planning phase (nullable — defaults to execution model)
 * @param planningTimeout timeout for planning phase (nullable — defaults to 5 minutes)
 * @param enableAnalysis whether to run the analysis phase
 * @param enablePlanning whether to run the planning phase
 */
public record PipelineConfig(@Nullable Path knowledgeDir, Map<String, Path> toolPaths,
		@Nullable String targetBootVersion, @Nullable String targetJavaVersion, @Nullable String planningModel,
		@Nullable Duration planningTimeout, boolean enableAnalysis, boolean enablePlanning) {

	private static final Duration DEFAULT_PLANNING_TIMEOUT = Duration.ofMinutes(5);

	public PipelineConfig {
		toolPaths = Map.copyOf(toolPaths);
	}

	/** Resolve the effective planning timeout, falling back to the 5-minute default. */
	public Duration effectivePlanningTimeout() {
		return planningTimeout != null ? planningTimeout : DEFAULT_PLANNING_TIMEOUT;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private @Nullable Path knowledgeDir;

		private Map<String, Path> toolPaths = Map.of();

		private @Nullable String targetBootVersion;

		private @Nullable String targetJavaVersion;

		private @Nullable String planningModel;

		private @Nullable Duration planningTimeout;

		private boolean enableAnalysis = true;

		private boolean enablePlanning = true;

		private Builder() {
		}

		public Builder knowledgeDir(@Nullable Path knowledgeDir) {
			this.knowledgeDir = knowledgeDir;
			return this;
		}

		public Builder toolPaths(Map<String, Path> toolPaths) {
			this.toolPaths = toolPaths;
			return this;
		}

		public Builder targetBootVersion(@Nullable String targetBootVersion) {
			this.targetBootVersion = targetBootVersion;
			return this;
		}

		public Builder targetJavaVersion(@Nullable String targetJavaVersion) {
			this.targetJavaVersion = targetJavaVersion;
			return this;
		}

		public Builder planningModel(@Nullable String planningModel) {
			this.planningModel = planningModel;
			return this;
		}

		public Builder planningTimeout(@Nullable Duration planningTimeout) {
			this.planningTimeout = planningTimeout;
			return this;
		}

		public Builder enableAnalysis(boolean enableAnalysis) {
			this.enableAnalysis = enableAnalysis;
			return this;
		}

		public Builder enablePlanning(boolean enablePlanning) {
			this.enablePlanning = enablePlanning;
			return this;
		}

		public PipelineConfig build() {
			return new PipelineConfig(knowledgeDir, toolPaths, targetBootVersion, targetJavaVersion, planningModel,
					planningTimeout, enableAnalysis, enablePlanning);
		}

	}

}
