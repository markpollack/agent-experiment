package io.github.markpollack.experiment.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.client.AgentExecution;
import io.github.markpollack.agents.client.ApplicationCorrelation;
import io.github.markpollack.agents.client.ExecutionDecision;
import io.github.markpollack.experiment.runtime.AgentRecords.Attempt;

/**
 * Starts an actual Client async execution with an exact Experiment/Workflow association.
 * The caller owns the configured Client and its lifetime. This service creates no
 * provider, executor, timeout, retry or alternative blocking execution path.
 *
 * <p>
 * Invocation selection is atomic within this service instance only. A selected ID is
 * retained even when Client admission throws or returns an unusable handle: uncertain
 * admission must never silently replay provider work. This is not durable or
 * cross-instance idempotence.
 * </p>
 */
public final class AsyncClientInvocationService {

	private final AgentClient client;

	private final Set<String> selected = ConcurrentHashMap.newKeySet();

	/**
	 * @param client configured, caller-owned Client; construction starts no work
	 */
	public AsyncClientInvocationService(AgentClient client) {
		this.client = Objects.requireNonNull(client, "client");
	}

	/**
	 * Starts one execution without inventing an experiment-definition identity.
	 * @param attempt exact physical Experiment/Workflow attempt
	 * @param workspace existing subject directory
	 * @param prompt nonblank submitted goal
	 * @param executionBudget positive Client execution allowance, not a waiter timeout
	 * @return original Client handle associated with the original attempt
	 * @throws IOException if the subject directory cannot be resolved
	 */
	public StartedExecution start(Attempt attempt, Path workspace, String prompt, Duration executionBudget)
			throws IOException {
		return start(attempt, workspace, prompt, executionBudget, Optional.empty());
	}

	/**
	 * Starts one execution with an optional actual experiment-definition identity. All
	 * argument validation precedes invocation reservation and any Client call.
	 * @param attempt exact physical Experiment/Workflow attempt
	 * @param workspace existing subject directory, resolved to its real path
	 * @param prompt nonblank submitted goal
	 * @param executionBudget positive Client execution allowance representable in
	 * nanoseconds
	 * @param experimentDefinitionId actual definition identity, or empty; never the run
	 * ID by inference
	 * @return original Client handle associated with the original attempt
	 * @throws IOException if the subject directory cannot be resolved
	 */
	public StartedExecution start(Attempt attempt, Path workspace, String prompt, Duration executionBudget,
			Optional<String> experimentDefinitionId) throws IOException {
		Objects.requireNonNull(executionBudget, "executionBudget");
		if (executionBudget.isNegative() || executionBudget.isZero())
			throw new IllegalArgumentException("Positive executionBudget required");
		executionBudget.toNanos();
		return startSelected(attempt, workspace, prompt, executionBudget, experimentDefinitionId);
	}

	/**
	 * Starts without a request execution deadline. The caller must configure Client
	 * without a default timeout and configure its provider for untimed execution. This
	 * service does not override either caller-owned policy. Result waiting and
	 * cancellation use the original handle; resource cleanup remains Client-owned.
	 * @param attempt exact physical Experiment/Workflow attempt
	 * @param workspace existing subject directory
	 * @param prompt nonblank submitted goal
	 * @return original Client handle associated with the original attempt
	 * @throws IOException if the subject directory cannot be resolved
	 */
	public StartedExecution startUntimed(Attempt attempt, Path workspace, String prompt) throws IOException {
		return startUntimed(attempt, workspace, prompt, Optional.empty());
	}

	/**
	 * Starts without a request deadline and with an optional actual definition identity.
	 * Requires an untimed caller-configured Client/provider, as described by
	 * {@link #startUntimed(Attempt, Path, String)}.
	 * @param attempt exact physical Experiment/Workflow attempt
	 * @param workspace existing subject directory, resolved to its real path
	 * @param prompt nonblank submitted goal
	 * @param experimentDefinitionId actual definition identity, or empty
	 * @return original Client handle associated with the original attempt
	 * @throws IOException if the subject directory cannot be resolved
	 */
	public StartedExecution startUntimed(Attempt attempt, Path workspace, String prompt,
			Optional<String> experimentDefinitionId) throws IOException {
		return startSelected(attempt, workspace, prompt, null, experimentDefinitionId);
	}

	private StartedExecution startSelected(Attempt attempt, Path workspace, String prompt, Duration executionBudget,
			Optional<String> experimentDefinitionId) throws IOException {
		Objects.requireNonNull(attempt, "attempt");
		Objects.requireNonNull(workspace, "workspace");
		Objects.requireNonNull(experimentDefinitionId, "experimentDefinitionId");
		requireText(prompt, "prompt");
		experimentDefinitionId.ifPresent(value -> requireText(value, "experimentDefinitionId"));
		Path actualWorkspace = workspace.toRealPath();
		if (!Files.isDirectory(actualWorkspace))
			throw new IllegalArgumentException("Workspace must be a directory");
		ApplicationCorrelation correlation = new ApplicationCorrelation(experimentDefinitionId,
				Optional.of(attempt.row().runId()), Optional.of(attempt.invocationId()),
				Optional.of(attempt.workflowRunId()), Optional.of(attempt.workflowInvocationId()),
				Optional.of(attempt.workflowAttemptId()));
		if (!selected.add(attempt.invocationId()))
			throw new IllegalStateException("Experiment invocation already selected: " + attempt.invocationId());
		var request = client.goal(prompt).workingDirectory(actualWorkspace).correlation(correlation);
		if (executionBudget != null)
			request = request.executionTimeout(executionBudget);
		AgentExecution execution = request.runAsync();
		StartedExecution started = new StartedExecution(attempt, execution, correlation);
		started.decision();
		return started;
	}

	private static void requireText(String value, String name) {
		if (value == null || value.isBlank())
			throw new IllegalArgumentException("Nonblank " + name + " required");
	}

	/**
	 * Transient association, not a stored result or proof of native/archive acceptance.
	 * Observe and cancel through {@link #execution()}; its waiter timeout/interruption,
	 * elected outcome, resource settlement and recording remain Client-owned.
	 */
	public static final class StartedExecution {

		private final Attempt attempt;

		private final AgentExecution execution;

		private final ApplicationCorrelation correlation;

		private final String executionId;

		private final String sessionId;

		private StartedExecution(Attempt attempt, AgentExecution execution, ApplicationCorrelation correlation) {
			this.attempt = Objects.requireNonNull(attempt, "attempt");
			this.execution = Objects.requireNonNull(execution, "Client returned null execution");
			this.correlation = correlation;
			this.executionId = execution.executionId();
			this.sessionId = execution.sessionId();
			requireText(this.executionId, "Client executionId");
			requireText(this.sessionId, "Client sessionId");
		}

		/**
		 * @return exact original Experiment attempt
		 */
		public Attempt attempt() {
			return attempt;
		}

		/**
		 * @return actual Client execution; no outcome or wait semantics are wrapped
		 */
		public AgentExecution execution() {
			return execution;
		}

		/**
		 * Validates identity/correlation and returns the original elected decision
		 * unchanged. Empty means Client has not elected a decision; it is not a failure
		 * or replay grant.
		 * @return original Client decision, when present
		 */
		public Optional<ExecutionDecision> decision() {
			if (!executionId.equals(execution.executionId()) || !sessionId.equals(execution.sessionId()))
				throw new IllegalStateException("Client execution identity changed");
			Optional<ExecutionDecision> decision = Objects.requireNonNull(execution.decision(), "Client decision");
			decision.ifPresent(value -> {
				if (!executionId.equals(value.executionId()) || !sessionId.equals(value.sessionId())
						|| !correlation.equals(value.correlation()))
					throw new IllegalStateException("Client decision/Experiment attempt association mismatch");
			});
			return decision;
		}

	}

}
