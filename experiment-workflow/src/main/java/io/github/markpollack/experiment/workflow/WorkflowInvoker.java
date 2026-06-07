package io.github.markpollack.experiment.workflow;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.markpollack.experiment.agent.AgentInvocationException;
import io.github.markpollack.experiment.agent.AgentInvoker;
import io.github.markpollack.experiment.agent.InvocationContext;
import io.github.markpollack.experiment.agent.InvocationResult;
import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.workflow.core.AgentContext;
import io.github.markpollack.workflow.flows.workflow.StepTransition;
import io.github.markpollack.workflow.flows.workflow.TraceRecorder;
import io.github.markpollack.workflow.flows.workflow.Workflow;
import io.github.markpollack.workflow.flows.workflow.WorkflowExecutor;
import io.github.markpollack.workflow.journal.WorkflowJournal;

/**
 * Base class for experiment invokers backed by the agent-workflow DSL.
 *
 * <p>
 * Wires the experiment loop ({@link AgentInvoker}) to a typed workflow execution,
 * recording each step into the journal and surfacing aggregated cost and token metrics in
 * the returned {@link InvocationResult}.
 *
 * <h2>How to use</h2> <pre>{@code
 * // In package io.github.markpollack.experiment.workflow (or your own package):
 * public class BomSyncInvoker extends WorkflowInvoker<BomSyncState> {
 *
 *     \@Override protected String workflowName() { return "bom-sync"; }
 *
 *     \@Override
 *     protected Workflow<Object, BomSyncState> buildWorkflow(
 *             InvocationContext ctx, WorkflowExecutor executor) {
 *         return Workflow.<Object, BomSyncState>define(workflowName())
 *                 .withExecutor(executor)
 *                 .step(checkMavenCentral)
 *                 .step(updateBomFile)
 *                 .build();
 *     }
 *
 *     \@Override
 *     protected BomSyncState buildInitialState(InvocationContext ctx) {
 *         return new BomSyncState(ctx.workspacePath());
 *     }
 * }
 * }</pre>
 *
 * <p>
 * Each workflow execution opens a journal {@link Run} under the experiment named
 * {@link #workflowName()}. Step completions are recorded as typed
 * {@code WorkflowStepEvent} entries via the journal-backed {@code TraceRecorder}. Cost
 * and token totals flow back into {@link InvocationResult} so the experiment framework
 * can track them normally.
 *
 * <h2>Journal configuration</h2>
 * <p>
 * Configure the journal storage once at startup before creating invokers: <pre>{@code
 * Journal.configure(new JsonFileStorage(Path.of("experiments", "traces", ".agent-journal")));
 * }</pre>
 *
 * @param <S> the workflow state type passed between steps
 * @see WorkflowAgentInvoker
 */
public abstract class WorkflowInvoker<S> implements AgentInvoker {

	@Override
	public final InvocationResult invoke(InvocationContext context) throws AgentInvocationException {
		WorkflowJournal.registerEventType();

		long start = System.currentTimeMillis();
		try (Run run = Journal.run(workflowName()).start()) {
			var costTracker = new CostTrackingRecorder(WorkflowJournal.forRun(run));
			var executor = new WorkflowExecutor(costTracker);

			Workflow<Object, S> workflow = buildWorkflow(context, executor);
			S initialState = buildInitialState(context);

			AgentContext agentCtx = AgentContext.withRunId(run.id())
				.mutate()
				.with(AgentContext.WORKFLOW_NAME, workflowName())
				.build();

			workflow.execute(agentCtx, initialState);

			long durationMs = System.currentTimeMillis() - start;
			Map<String, String> metadata = new HashMap<>(context.metadata());
			enrichMetadata(context, metadata);

			return InvocationResult.completed(List.of(), 0, (int) costTracker.totalTokens(), 0, costTracker.totalCost(),
					durationMs, run.id(), metadata);
		}
		catch (Exception ex) {
			throw new AgentInvocationException("Workflow execution failed: " + ex.getMessage(), ex);
		}
	}

	/**
	 * Name of this workflow — used as both the workflow DSL name and the journal
	 * experiment identifier.
	 */
	protected abstract String workflowName();

	/**
	 * Build the workflow to execute. The {@code executor} is pre-wired with a
	 * journal-backed {@code TraceRecorder}; inject it via {@code .withExecutor(executor)}
	 * on the workflow builder.
	 * @param context the invocation context (workspace, prompt, model, metadata)
	 * @param executor the journal-backed workflow executor to inject
	 * @return a fully built {@link Workflow} ready to execute
	 */
	protected abstract Workflow<Object, S> buildWorkflow(InvocationContext context, WorkflowExecutor executor);

	/**
	 * Construct the initial state passed to the first workflow step.
	 * @param context the invocation context
	 * @return the starting state for this invocation
	 */
	protected abstract S buildInitialState(InvocationContext context);

	/**
	 * Optionally enrich the result metadata after workflow execution. Override to add
	 * domain-specific entries (e.g., artifacts produced, version bumped).
	 * @param context the original invocation context
	 * @param metadata mutable metadata map to enrich
	 */
	protected void enrichMetadata(InvocationContext context, Map<String, String> metadata) {
	}

	/**
	 * Wraps a delegate {@link TraceRecorder} and accumulates per-step token and cost
	 * totals so they can be surfaced in {@link InvocationResult}.
	 */
	private static final class CostTrackingRecorder implements TraceRecorder {

		private final TraceRecorder delegate;

		private long totalTokens = 0;

		private double totalCost = 0.0;

		CostTrackingRecorder(TraceRecorder delegate) {
			this.delegate = delegate;
		}

		@Override
		public void record(StepTransition transition) {
			delegate.record(transition);
			totalTokens += transition.tokensUsed();
			totalCost += transition.costUsd();
		}

		@Override
		public List<StepTransition> getTrace(String workflowRunId) {
			return delegate.getTrace(workflowRunId);
		}

		long totalTokens() {
			return totalTokens;
		}

		double totalCost() {
			return totalCost;
		}

	}

}
