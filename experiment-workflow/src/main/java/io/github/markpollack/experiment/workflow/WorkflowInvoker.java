package io.github.markpollack.experiment.workflow;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import io.github.markpollack.experiment.agent.*;
import io.github.markpollack.experiment.runtime.WorkflowAdapter;
import io.github.markpollack.experiment.runtime.AgentRecords.Identity;
import io.github.markpollack.workflow.flows.compiler.ValidatedWorkflow;
import io.github.markpollack.workflow.batch.durable.*;

/**
 * A current typed Workflow run with retained snapshot and explicitly unknown usage
 * totals.
 */
public abstract class WorkflowInvoker<S> implements AgentInvoker {

	/** Per-invocation graph, executable bindings and exact deployment identity. */
	public record Plan(ValidatedWorkflow definition, StepRegistry steps, ExecutionCompatibility deployment) {
	}

	protected abstract String workflowName();

	protected abstract Plan buildWorkflow(InvocationContext context);

	protected abstract S buildInitialState(InvocationContext context);

	protected void enrichMetadata(InvocationContext context, Map<String, String> metadata) {
	}

	protected boolean outputSucceeded(Object value) {
		return true;
	}

	protected void terminateProvider() {
	}

	@Override
	public final InvocationResult invoke(InvocationContext context) throws AgentInvocationException {
		long start = System.nanoTime();
		try {
			Plan plan = Objects.requireNonNull(buildWorkflow(context));
			Path root = context.workspacePath().resolve(".experiment-workflow");
			Files.createDirectories(root);
			String id = UUID.randomUUID().toString();
			var identity = Identity.of(context.metadata().getOrDefault("experimentId", id),
					context.metadata().getOrDefault("itemId", id),
					context.metadata().getOrDefault("variant", "default"), 0);
			var execution = new WorkflowAdapter().execute(root.resolve(id), plan.definition(), plan.steps(),
					plan.deployment(), identity, buildInitialState(context), Duration.ofSeconds(1),
					this::terminateProvider);
			var metadata = new LinkedHashMap<>(context.metadata());
			metadata.put("measurementState", "UNKNOWN");
			metadata.put("measurementReason", "Workflow completion is not a provider usage measurement");
			metadata.put("workflowRunId", execution.snapshot().runId());
			metadata.put("workflowState", execution.snapshot().status().name());
			metadata.put("workflowLifecycle", execution.lifecycle());
			metadata.put("workflowSnapshot", execution.snapshotFile().path());
			metadata.put("workflowSnapshotSha256", execution.snapshotFile().sha256());
			metadata.put("workflowResult",
					new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(execution.value()));
			enrichMetadata(context, metadata);
			boolean success = execution.accepted() && outputSucceeded(execution.value());
			return new InvocationResult(success, success ? TerminalStatus.COMPLETED : TerminalStatus.ERROR, List.of(),
					0, 0, 0, 0, 0, 0.0, (System.nanoTime() - start) / 1_000_000, execution.snapshot().runId(), metadata,
					success ? null : "Workflow output not accepted");
		}
		catch (Exception failure) {
			throw new AgentInvocationException("Workflow execution failed: " + failure.getMessage(), failure);
		}
	}

}
