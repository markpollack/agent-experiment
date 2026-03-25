package io.github.markpollack.experiment.pipeline;

/**
 * Thrown when project analysis fails (I/O errors, malformed project files).
 *
 * <p>
 * Unchecked because analysis failures are handled by graceful degradation in
 * {@link PipelineAgentInvoker} — the pipeline skips analysis and continues with
 * planning/execution.
 */
public class AnalysisException extends RuntimeException {

	public AnalysisException(String message) {
		super(message);
	}

	public AnalysisException(String message, Throwable cause) {
		super(message, cause);
	}

}
