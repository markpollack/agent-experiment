package io.github.markpollack.experiment.agent.claude;

import java.nio.file.Path;
import java.util.List;

import io.github.markpollack.journal.claude.PhaseCapture;
import io.github.markpollack.journal.claude.SessionLogParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.claude.agent.sdk.ClaudeClient;
import org.springaicommunity.claude.agent.sdk.ClaudeSyncClient;
import org.springaicommunity.claude.agent.sdk.transport.CLIOptions;

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
 * {@link io.github.markpollack.experiment.runner.ExperimentRunner}.
 *
 * <p>
 * Thread-safe: each invocation creates its own {@link ClaudeSyncClient}.
 */
public class ClaudeSdkInvoker implements AgentInvoker {

	private static final Logger log = LoggerFactory.getLogger(ClaudeSdkInvoker.class);

	private final ClaudeSdkInvokerConfig config;

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
