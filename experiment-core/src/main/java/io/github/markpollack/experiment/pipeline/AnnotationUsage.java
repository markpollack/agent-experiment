package io.github.markpollack.experiment.pipeline;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Records a single annotation usage found during project analysis.
 *
 * @param annotation fully qualified annotation name (e.g.,
 * "org.springframework.boot.autoconfigure.SpringBootApplication")
 * @param file source file path relative to workspace
 * @param className class where the annotation appears (nullable for package-level
 * annotations)
 */
public record AnnotationUsage(String annotation, String file, @Nullable String className) {

	public AnnotationUsage {
		Objects.requireNonNull(annotation, "annotation must not be null");
		Objects.requireNonNull(file, "file must not be null");
	}

}
