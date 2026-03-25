package io.github.markpollack.experiment.agent;

/**
 * Thrown when an agent invocation fails with an unrecoverable error. Recoverable issues
 * are reflected in {@link InvocationResult#status()} instead.
 */
public class AgentInvocationException extends Exception {

	public AgentInvocationException(String message) {
		super(message);
	}

	public AgentInvocationException(String message, Throwable cause) {
		super(message, cause);
	}

}
