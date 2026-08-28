package io.github.markpollack.experiment.agent.claude;

import java.nio.file.Path;
import java.util.List;

import io.github.markpollack.journal.claude.PhaseCapture;
import io.github.markpollack.journal.claude.SessionLogParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.markpollack.claude.agent.sdk.ClaudeClient;
import io.github.markpollack.claude.agent.sdk.ClaudeSyncClient;
import io.github.markpollack.claude.agent.sdk.transport.CLIOptions;

import io.github.markpollack.experiment.agent.AgentInvocationException;
import io.github.markpollack.experiment.agent.AgentInvoker;
import io.github.markpollack.experiment.agent.InvocationContext;
import io.github.markpollack.experiment.agent.InvocationResult;

/**
 * {@link AgentInvoker} implementation that invokes Claude CLI via the
 * {@code claude-code-sdk}.
 *
 * <p>
 * Executes a single-phase invocation: one {@code connect(prompt)} +
 * {@code receiveResponse()} per item. The prompt is already fully constructed by
 * {@link io.github.markpollack.experiment.runner.AgentExperiment}.
 *
 * <p>
 * Thread-safe: each invocation creates its own {@link ClaudeSyncClient}.
 */
public class ClaudeSdkInvoker implements AgentInvoker {

	private static final Logger log = LoggerFactory.getLogger(ClaudeSdkInvoker.class);

	private final ClaudeSdkInvokerConfig config;

	/**
	 * Declares the options that govern how much work the agent may do.
	 *
	 * <p>
	 * These were previously applied and never recorded: the ceiling reached the SDK and
	 * stopped there, so two runs at different ceilings were compared as though alike.
	 * Declaring them here is what puts them in the run record. Only limits are declared —
	 * the values that change what a run was permitted to do, and therefore whether two
	 * runs are comparable.
	 */
	@Override
	public java.util.Map<String, String> declaredConditions() {
		java.util.Map<String, String> declared = new java.util.TreeMap<>();
		declared.put("maxTurns", String.valueOf(this.config.maxTurns()));
		declared.put("maxBudgetUsd", String.valueOf(this.config.maxBudgetUsd()));
		declared.put("maxThinkingTokens", String.valueOf(this.config.maxThinkingTokens()));
		declared.put("permissionMode", String.valueOf(this.config.permissionMode()));
		return declared;
	}

	public ClaudeSdkInvoker(ClaudeSdkInvokerConfig config) {
		this.config = java.util.Objects.requireNonNull(config, "config must not be null");
	}

	@Override
	public InvocationResult invoke(InvocationContext context) throws AgentInvocationException {
		CLIOptions options = buildOptions(context);
		long start = System.currentTimeMillis();

		try (ClaudeSyncClient client = ClaudeClient.sync(options).workingDirectory(context.workspacePath()).build()) {

			log.debug("Invoking Claude on {} with model {}", context.workspacePath(), context.model());
			client.connect(context.prompt());
			Path traceFile = context.runDir() != null ? context.runDir()
				.resolve("agent-trace-" + context.metadata().getOrDefault("itemSlug", "unknown") + "-invoke.jsonl")
					: null;
			PhaseCapture capture = SessionLogParser.parse(client.receiveResponse(), "invoke", context.prompt(),
					traceFile);
			long durationMs = System.currentTimeMillis() - start;

			if (capture.isError()) {
				return InvocationResult.fromPhases(List.of(capture), durationMs, capture.sessionId(),
						context.metadata());
			}

			return InvocationResult.fromPhases(List.of(capture), durationMs, capture.sessionId(), context.metadata());
		}
		catch (Exception ex) {
			long durationMs = System.currentTimeMillis() - start;
			log.error("Claude SDK invocation failed after {}ms", durationMs, ex);
			throw new AgentInvocationException("Claude SDK invocation failed: " + ex.getMessage(), ex);
		}
	}

	/**
	 * Builds CLIOptions from the invocation context and config. Package-private for
	 * testability.
	 */
	CLIOptions buildOptions(InvocationContext context) {
		String resolvedModel = resolveModelId(context.model());

		CLIOptions.Builder builder = CLIOptions.builder()
			.permissionMode(config.permissionMode())
			.model(resolvedModel)
			.timeout(context.timeout());

		if (context.systemPrompt() != null) {
			builder.appendSystemPrompt(context.systemPrompt());
		}
		if (config.maxBudgetUsd() != null) {
			builder.maxBudgetUsd(config.maxBudgetUsd());
		}
		if (config.maxTurns() != null) {
			builder.maxTurns(config.maxTurns());
		}
		if (config.maxThinkingTokens() != null) {
			builder.maxThinkingTokens(config.maxThinkingTokens());
		}

		return builder.build();
	}

	/**
	 * Resolves short model names to full Claude model IDs. Package-private for
	 * testability.
	 */
	static String resolveModelId(String name) {
		return switch (name.toLowerCase()) {
			case "sonnet" -> CLIOptions.MODEL_SONNET;
			case "haiku" -> CLIOptions.MODEL_HAIKU;
			case "opus" -> CLIOptions.MODEL_OPUS;
			default -> name;
		};
	}

}
