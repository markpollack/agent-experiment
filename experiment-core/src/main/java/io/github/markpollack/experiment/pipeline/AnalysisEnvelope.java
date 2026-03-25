package io.github.markpollack.experiment.pipeline;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

/**
 * Structured analysis output from a {@link ProjectAnalyzer}. Contains all
 * upgrade-relevant facts about a project workspace.
 *
 * <p>
 * Serializable as JSON for caching and debugging.
 *
 * @param projectName project name (from POM artifactId or directory name)
 * @param bootVersion detected Spring Boot version (nullable if not a Boot project)
 * @param javaVersion detected Java version (nullable)
 * @param buildTool build tool identifier ("maven", "gradle", or "unknown")
 * @param parentCoordinates parent POM coordinates groupId:artifactId:version (nullable)
 * @param dependencies dependency coordinates to version mapping
 * @param importPatterns import namespace to list of files containing it
 * @param annotations annotation usages found in source
 * @param configFiles config file paths relative to workspace
 * @param modules Maven module names (empty for single-module projects)
 * @param metadata extensible metadata (analysis duration, file counts, etc.)
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record AnalysisEnvelope(String projectName, @Nullable String bootVersion, @Nullable String javaVersion,
		String buildTool, @Nullable String parentCoordinates, Map<String, String> dependencies,
		Map<String, List<String>> importPatterns, List<AnnotationUsage> annotations, List<String> configFiles,
		List<String> modules, Map<String, Object> metadata) {

	public AnalysisEnvelope {
		Objects.requireNonNull(projectName, "projectName must not be null");
		Objects.requireNonNull(buildTool, "buildTool must not be null");
		dependencies = dependencies != null ? Map.copyOf(dependencies) : Map.of();
		importPatterns = importPatterns != null ? Map.copyOf(importPatterns) : Map.of();
		annotations = annotations != null ? List.copyOf(annotations) : List.of();
		configFiles = configFiles != null ? List.copyOf(configFiles) : List.of();
		modules = modules != null ? List.copyOf(modules) : List.of();
		metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private String projectName;

		private @Nullable String bootVersion;

		private @Nullable String javaVersion;

		private String buildTool = "unknown";

		private @Nullable String parentCoordinates;

		private Map<String, String> dependencies = Map.of();

		private Map<String, List<String>> importPatterns = Map.of();

		private List<AnnotationUsage> annotations = List.of();

		private List<String> configFiles = List.of();

		private List<String> modules = List.of();

		private Map<String, Object> metadata = Map.of();

		private Builder() {
		}

		public Builder projectName(String projectName) {
			this.projectName = projectName;
			return this;
		}

		public Builder bootVersion(@Nullable String bootVersion) {
			this.bootVersion = bootVersion;
			return this;
		}

		public Builder javaVersion(@Nullable String javaVersion) {
			this.javaVersion = javaVersion;
			return this;
		}

		public Builder buildTool(String buildTool) {
			this.buildTool = buildTool;
			return this;
		}

		public Builder parentCoordinates(@Nullable String parentCoordinates) {
			this.parentCoordinates = parentCoordinates;
			return this;
		}

		public Builder dependencies(Map<String, String> dependencies) {
			this.dependencies = dependencies;
			return this;
		}

		public Builder importPatterns(Map<String, List<String>> importPatterns) {
			this.importPatterns = importPatterns;
			return this;
		}

		public Builder annotations(List<AnnotationUsage> annotations) {
			this.annotations = annotations;
			return this;
		}

		public Builder configFiles(List<String> configFiles) {
			this.configFiles = configFiles;
			return this;
		}

		public Builder modules(List<String> modules) {
			this.modules = modules;
			return this;
		}

		public Builder metadata(Map<String, Object> metadata) {
			this.metadata = metadata;
			return this;
		}

		public AnalysisEnvelope build() {
			return new AnalysisEnvelope(projectName, bootVersion, javaVersion, buildTool, parentCoordinates,
					dependencies, importPatterns, annotations, configFiles, modules, metadata);
		}

	}

}
