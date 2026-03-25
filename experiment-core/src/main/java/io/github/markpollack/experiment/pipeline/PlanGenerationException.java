package io.github.markpollack.experiment.pipeline;

/**
 * Thrown when plan generation fails (Claude session failure, timeout, unparseable
 * output).
 *
 * <p>
 * Checked exception because callers must explicitly decide how to handle planning
 * failures — typically by degrading to execution without a plan.
 */
public class PlanGenerationException extends Exception {

	public PlanGenerationException(String message) {
		super(message);
	}

	public PlanGenerationException(String message, Throwable cause) {
		super(message, cause);
	}

}
