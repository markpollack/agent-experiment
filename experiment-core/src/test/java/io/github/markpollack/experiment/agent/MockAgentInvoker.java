package io.github.markpollack.experiment.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * Test double for {@link AgentInvoker}. Supports configurable per-item results, default
 * results, error injection, and invocation recording.
 */
public class MockAgentInvoker implements AgentInvoker {

	private final Map<String, Function<InvocationContext, InvocationResult>> itemHandlers = new LinkedHashMap<>();

	private Function<InvocationContext, InvocationResult> defaultHandler = MockAgentInvoker::defaultSuccess;

	private @Nullable String defaultErrorMessage;

	private final java.util.List<InvocationContext> invocations = new java.util.ArrayList<>();

	@Override
	public InvocationResult invoke(InvocationContext context) throws AgentInvocationException {
		invocations.add(context);
		if (defaultErrorMessage != null) {
			String itemId = context.metadata().get("itemId");
			if (itemId == null || !itemHandlers.containsKey(itemId)) {
				throw new AgentInvocationException(defaultErrorMessage);
			}
		}
		String itemId = context.metadata().get("itemId");
		if (itemId != null && itemHandlers.containsKey(itemId)) {
			return itemHandlers.get(itemId).apply(context);
		}
		return defaultHandler.apply(context);
	}

	/**
	 * Register a handler for a specific item ID.
	 * @param itemId the item ID to match (from context metadata)
	 * @param handler function that produces the result for this item
	 * @return this invoker for chaining
	 */
	public MockAgentInvoker onItem(String itemId, Function<InvocationContext, InvocationResult> handler) {
		itemHandlers.put(itemId, handler);
		return this;
	}

	/**
	 * Register a fixed result for a specific item ID.
	 * @param itemId the item ID to match
	 * @param result the result to return
	 * @return this invoker for chaining
	 */
	public MockAgentInvoker onItem(String itemId, InvocationResult result) {
		return onItem(itemId, ctx -> result);
	}

	/**
	 * Set the default handler for items without a specific handler.
	 * @param handler function that produces the default result
	 * @return this invoker for chaining
	 */
	public MockAgentInvoker defaultResult(Function<InvocationContext, InvocationResult> handler) {
		this.defaultHandler = handler;
		return this;
	}

	/**
	 * Set the default to throw an {@link AgentInvocationException}.
	 * @param message error message
	 * @return this invoker for chaining
	 */
	public MockAgentInvoker defaultError(String message) {
		this.defaultErrorMessage = message;
		return this;
	}

	/** Returns all invocation contexts received, in order. */
	public List<InvocationContext> invocations() {
		return List.copyOf(invocations);
	}

	/** Returns the number of times {@link #invoke} was called. */
	public int invocationCount() {
		return invocations.size();
	}

	private static InvocationResult defaultSuccess(InvocationContext context) {
		return InvocationResult.completed(List.of(), 100, 200, 50, 0.01, 1000, "mock-session", context.metadata());
	}

}
