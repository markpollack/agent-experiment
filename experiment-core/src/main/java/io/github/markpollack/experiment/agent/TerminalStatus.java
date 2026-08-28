package io.github.markpollack.experiment.agent;

/**
 * Terminal status of an agent invocation.
 */
public enum TerminalStatus {

	/** Agent completed successfully. */
	COMPLETED,

	/** Agent was terminated due to timeout. */
	TIMEOUT,

	/** Agent was cut off by its configured turn ceiling. Distinct from failing. */
	MAX_TURNS,

	/** Agent encountered an unrecoverable error. */
	ERROR

}
