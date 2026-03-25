package io.github.markpollack.experiment.pipeline;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Configuration for project analysis.
 *
 * @param strategy analysis strategy to use
 * @param targetBootVersion target Spring Boot version (nullable)
 * @param targetJavaVersion target Java version (nullable)
 */
public record AnalysisConfig(AnalysisStrategy strategy, @Nullable String targetBootVersion,
		@Nullable String targetJavaVersion) {

	public AnalysisConfig {
		Objects.requireNonNull(strategy, "strategy must not be null");
	}

	/** Create a POM-only analysis config with no target versions. */
	public static AnalysisConfig pomOnly() {
		return new AnalysisConfig(AnalysisStrategy.POM_ONLY, null, null);
	}

	/** Create a POM-only analysis config with target versions. */
	public static AnalysisConfig pomOnly(String targetBootVersion, String targetJavaVersion) {
		return new AnalysisConfig(AnalysisStrategy.POM_ONLY, targetBootVersion, targetJavaVersion);
	}

}
