package io.github.markpollack.experiment.agent;

/**
 * Terminal status of an agent invocation.
 */
public enum TerminalStatus {

	/** Agent completed successfully. */
	COMPLETED,

	/** Agent was terminated due to timeout. */
	TIMEOUT,

	/** Agent encountered an unrecoverable error. */
	ERROR

}
