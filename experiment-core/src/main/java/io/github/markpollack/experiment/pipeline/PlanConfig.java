package io.github.markpollack.experiment.pipeline;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Configuration for the planning phase.
 *
 * @param knowledgeDir knowledge store root directory (nullable — no knowledge store)
 * @param toolPaths tool name to executable JAR path mapping
 * @param targetBootVersion target Spring Boot version (nullable)
 * @param targetJavaVersion target Java version (nullable)
 * @param model model to use for the planning session
 * @param timeout timeout for the planning session
 * @param runDir optional directory for run artifacts (trace files)
 */
public record PlanConfig(@Nullable Path knowledgeDir, Map<String, Path> toolPaths, @Nullable String targetBootVersion,
		@Nullable String targetJavaVersion, String model, Duration timeout, @Nullable Path runDir) {

	public PlanConfig(@Nullable Path knowledgeDir, Map<String, Path> toolPaths, @Nullable String targetBootVersion,
			@Nullable String targetJavaVersion, String model, Duration timeout) {
		this(knowledgeDir, toolPaths, targetBootVersion, targetJavaVersion, model, timeout, null);
	}

	public PlanConfig {
		Objects.requireNonNull(model, "model must not be null");
		Objects.requireNonNull(timeout, "timeout must not be null");
		toolPaths = Map.copyOf(toolPaths);
	}

}
