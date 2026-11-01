package io.github.markpollack.experiment.agent.client;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import io.github.markpollack.agents.claude.ClaudeAgentModel;
import io.github.markpollack.agents.claude.ClaudeAgentOptions;
import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.client.AgentClientResponse;
import io.github.markpollack.agents.model.AgentApi;
import io.github.markpollack.agents.model.AgentOptions;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.markpollack.experiment.agent.AgentInvocationException;
import io.github.markpollack.experiment.agent.AgentInvoker;
import io.github.markpollack.experiment.agent.InvocationContext;
import io.github.markpollack.experiment.agent.InvocationResult;

/**
 * {@link AgentInvoker} that drives an agent CLI <em>through</em> {@link AgentClient}, so
 * the harness speaks one provider-neutral API rather than a vendor SDK.
 *
 * <p>
 * Any {@link AgentApi} implementation works. The convenience default is
 * {@link ClaudeAgentModel}, built per invocation against the context's workspace; pass an
 * {@link AgentApi} to a constructor overload to drive a different provider, in which case
 * the caller owns the model's lifecycle.
 *
 * <h2>Configuration is the provider's own options type</h2>
 *
 * <p>
 * There is deliberately no configuration record here to mirror. Per-invocation settings
 * come from an {@link AgentOptions} factory, so a caller supplies
 * {@link ClaudeAgentOptions}, some other provider's options, or the portable
 * {@code DefaultAgentOptions} — and reaching a provider setting never requires an edit to
 * this class.
 *
 * <h2>Evidence and measurements</h2> Bare Client replies retain response text and
 * explicit UNKNOWN measurement metadata. No legacy phase capture is copied or parsed. Use
 * the canonical runtime acquisition service when original-byte evidence and Journal
 * assembly are required.
 *
 * <p>
 * Thread-safe: each invocation builds its own client. When an {@link AgentApi} is
 * injected, its thread-safety is the caller's to guarantee.
 */
public class AgentClientInvoker implements AgentInvoker {

	private static final Logger log = LoggerFactory.getLogger(AgentClientInvoker.class);

	private final @Nullable AgentApi agentApi;

	private final Function<InvocationContext, AgentOptions> optionsFactory;

	/** Uses {@link ClaudeAgentModel} with options derived from the invocation context. */
	public AgentClientInvoker() {
		this(null, AgentClientInvoker::claudeOptions);
	}

	/**
	 * Uses {@link ClaudeAgentModel} with caller-supplied options.
	 * @param optionsFactory builds the provider options for each invocation
	 */
	public AgentClientInvoker(Function<InvocationContext, AgentOptions> optionsFactory) {
		this(null, optionsFactory);
	}

	/**
	 * Uses an injected model with options derived from the invocation context.
	 * @param agentApi the model to drive; the caller owns its lifecycle
	 */
	public AgentClientInvoker(AgentApi agentApi) {
		this(Objects.requireNonNull(agentApi, "agentApi must not be null"), AgentClientInvoker::claudeOptions);
	}

	/**
	 * Uses an injected model with caller-supplied options.
	 * @param agentApi the model to drive, or null for the {@link ClaudeAgentModel}
	 * default; when non-null the caller owns its lifecycle
	 * @param optionsFactory builds the provider options for each invocation
	 */
	public AgentClientInvoker(@Nullable AgentApi agentApi, Function<InvocationContext, AgentOptions> optionsFactory) {
		this.agentApi = agentApi;
		this.optionsFactory = Objects.requireNonNull(optionsFactory, "optionsFactory must not be null");
	}

	@Override
	public InvocationResult invoke(InvocationContext context) throws AgentInvocationException {
		AgentOptions options = optionsFactory.apply(context);
		long start = System.currentTimeMillis();

		try {
			log.debug("Invoking agent on {} with model {}", context.workspacePath(), context.model());

			if (this.agentApi != null) {
				return toResult(AgentClient.create(this.agentApi).run(context.prompt(), options), context, start);
			}

			try (ClaudeAgentModel model = ClaudeAgentModel.builder()
				.workingDirectory(context.workspacePath())
				.timeout(context.timeout())
				.build()) {
				return toResult(AgentClient.create(model).run(context.prompt(), options), context, start);
			}
		}
		catch (Exception ex) {
			long durationMs = System.currentTimeMillis() - start;
			log.error("Agent invocation failed after {}ms", durationMs, ex);
			throw new AgentInvocationException("Agent invocation failed: " + ex.getMessage(), ex);
		}
	}

	/**
	 * Converts a client response into an {@link InvocationResult}, preferring the
	 * provider's own phase capture. Package-private for testability.
	 */
	InvocationResult toResult(AgentClientResponse response, InvocationContext context, long start) {
		long durationMs = System.currentTimeMillis() - start;
		var metadata = new java.util.LinkedHashMap<>(context.metadata());
		metadata.put("measurementState", "UNKNOWN");
		metadata.put("measurementReason", "No qualified canonical acquisition usage in this response adapter");
		metadata.put("agentResponse", Objects.toString(response.getResult(), ""));
		return new InvocationResult(response.isSuccessful(),
				response.isSuccessful() ? io.github.markpollack.experiment.agent.TerminalStatus.COMPLETED
						: io.github.markpollack.experiment.agent.TerminalStatus.ERROR,
				List.of(), 0, 0, 0, 0, 0, 0.0, durationMs, response.getMetadata().getSessionId(), metadata,
				response.isSuccessful() ? null : "Agent reported failure");
	}

	/**
	 * The convenience default: {@link ClaudeAgentOptions} carrying only what the
	 * invocation context supplies. Model names pass through untranslated — the CLI
	 * resolves its own aliases, so no model table is pinned here.
	 */
	static AgentOptions claudeOptions(InvocationContext context) {
		ClaudeAgentOptions.Builder builder = ClaudeAgentOptions.builder()
			.model(context.model())
			.timeout(context.timeout())
			.workingDirectory(context.workspacePath().toString())
			.yolo(true);

		if (context.systemPrompt() != null) {
			builder.appendSystemPrompt(context.systemPrompt());
		}

		return builder.build();
	}

}
