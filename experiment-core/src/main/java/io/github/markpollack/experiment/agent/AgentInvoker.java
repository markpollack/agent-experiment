package io.github.markpollack.experiment.agent;

/**
 * Abstraction for invoking an AI agent on a workspace. Implementations hide the
 * architecture difference between Claude SDK (opaque loop) and harness-managed (explicit
 * loop) execution paths.
 *
 * <p>
 * Contract:
 * <ul>
 * <li>Blocking: returns when agent completes, times out, or fails</li>
 * <li>Thread-safe: a single instance may be called from multiple threads</li>
 * <li>Idempotent per context: calling with the same InvocationContext produces a
 * logically independent result</li>
 * </ul>
 *
 * <p>
 * Invoker responsibilities: session setup, waiting for completion, parsing response into
 * PhaseCapture records.
 *
 * <p>
 * NOT responsible for: timeout enforcement (caller wraps in Future/ExecutorService),
 * tracking/recording, Git workspace management.
 */
@FunctionalInterface
public interface AgentInvoker {

	/**
	 * Invoke the agent with the given context.
	 * @param context invocation context including workspace, prompt, model, and timeout
	 * hint
	 * @return result of the invocation including phases, tokens, cost, and status
	 * @throws AgentInvocationException for unrecoverable failures. Recoverable issues
	 * (agent reports error status) are reflected in {@link InvocationResult#status()} ==
	 * {@link TerminalStatus#ERROR} with {@link InvocationResult#success()} == false.
	 */
	InvocationResult invoke(InvocationContext context) throws AgentInvocationException;

	/**
	 * The conditions this invoker will actually apply, for the run record.
	 *
	 * <p>
	 * An invoker's configuration does not otherwise cross into
	 * {@code ExperimentConfig}/{@code ExperimentResult}: a turn ceiling was once set
	 * here, applied during the run, and never recorded, so two runs permitted different
	 * amounts of work were compared as though they were alike. Declaring the ceiling here
	 * is what puts it in the record.
	 *
	 * <p>
	 * The default returns empty, meaning <em>did not declare</em> rather than <em>no
	 * conditions</em>. A run including an invoker that declares nothing is marked
	 * incomplete and will not be compared. Override this in any invoker whose
	 * configuration governs how much work the agent may do.
	 * @return condition name to value; empty means undeclared
	 */
	default java.util.Map<String, String> declaredConditions() {
		return java.util.Map.of();
	}

}
