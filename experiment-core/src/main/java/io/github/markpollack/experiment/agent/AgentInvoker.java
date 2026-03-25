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

}
