package io.github.markpollack.experiment.runtime;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import io.github.markpollack.workflow.batch.durable.*;
import io.github.markpollack.workflow.flows.compiler.ValidatedWorkflow;
import io.github.markpollack.experiment.runtime.AgentRecords.*;

/**
 * Released runtime adapter. One fresh store/launch, one attempt, no implicit resume after
 * failure.
 */
public final class WorkflowAdapter {

	public record Execution(Identity identity, RunSnapshot snapshot, Object value, String lifecycle,
			FileRef snapshotFile) {
		public boolean accepted() {
			return snapshot.status() == RunSnapshot.Status.SUCCEEDED && lifecycle.equals("DRAINED");
		}
	}

	public Execution execute(Path freshDirectory, ValidatedWorkflow definition, StepRegistry steps,
			ExecutionCompatibility deployment, Identity identity, Object input, Duration drainBudget,
			Runnable terminateProvider) throws IOException {
		AgentRecords.require(!drainBudget.isNegative() && !drainBudget.isZero(), "Positive drain budget required");
		Files.createDirectory(freshDirectory); // never reuse a previous launch as another
												// measurement
		DurableWorkflows runtime = DurableWorkflows.open(freshDirectory.resolve("workflow"), steps, deployment,
				new ExecutionPolicy(1, 32, 10000, 1));
		ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
		RunSnapshot snapshot = null;
		Object value = null;
		boolean drained = false;
		try {
			snapshot = runtime.start(definition, identity.launchKey(), input);
			String runId = snapshot.runId();
			Future<RunSnapshot> future = worker.submit(() -> runtime.resume(runId, definition));
			long remaining = Math.max(1, Duration.between(java.time.Instant.now(), snapshot.deadline()).toMillis());
			try {
				snapshot = future.get(remaining, TimeUnit.MILLISECONDS);
			}
			catch (TimeoutException | InterruptedException interrupted) {
				// Revocation is a database operation, never an interrupt of H2's worker.
				snapshot = runtime.cancel(runId, "experiment", "Outer workflow bound reached or caller interrupted");
				requestTermination(terminateProvider);
				if (interrupted instanceof InterruptedException)
					Thread.currentThread().interrupt();
			}
			catch (ExecutionException failure) {
				snapshot = runtime.inspect(runId);
				if (!snapshot.terminal())
					snapshot = runtime.cancel(runId, "experiment",
							"Resume threw: " + failure.getCause().getClass().getName());
				requestTermination(terminateProvider);
			}
			if (snapshot.status() == RunSnapshot.Status.SUCCEEDED)
				value = runtime.result(runId, definition);
		}
		finally {
			worker.shutdown();
			// shutdown() is bounded; uncooperative external effects retain their
			// workspace and evidence.
			drained = runtime.shutdown(drainBudget);
			if (!drained) {
				requestTermination(terminateProvider);
				Thread.ofPlatform().daemon().name("experiment-quarantine-drain").start(() -> {
					try {
						while (!worker.awaitTermination(1, TimeUnit.SECONDS)) {
							/* retained owner */ }
						runtime.shutdown(drainBudget);
					}
					catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				});
			}
		}
		if (snapshot == null)
			throw new IOException("Workflow admission failed; retained fresh store at " + freshDirectory);
		Path receipt = freshDirectory.resolve("snapshot.json");
		Files.write(receipt, RuntimeJson.mapper().writeValueAsBytes(snapshot), StandardOpenOption.CREATE_NEW);
		return new Execution(identity, snapshot, value, drained ? "DRAINED" : "QUARANTINED",
				Evidence.reference(receipt, "workflow-snapshot"));
	}

	private static void requestTermination(Runnable terminate) {
		Thread.ofPlatform().daemon().name("experiment-provider-termination").start(terminate);
	}

	/**
	 * Validate the exact physical delivery against the retained released-runtime
	 * snapshot.
	 */
	public static void validateAttempt(Execution execution, Attempt attempt, boolean requireCommitted) {
		AgentRecords.require(execution.identity().equals(attempt.row()), "Wrong full Workflow row");
		AgentRecords.require(execution.snapshot().runId().equals(attempt.workflowRunId()), "Wrong Workflow run");
		var logical = execution.snapshot()
			.invocations()
			.stream()
			.filter(i -> i.invocationId().equals(attempt.workflowInvocationId()))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Unknown Workflow invocation"));
		var physical = logical.attempts()
			.stream()
			.filter(a -> a.attemptId().equals(attempt.workflowAttemptId()) && a.number() == attempt.attemptNumber())
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Wrong Workflow physical attempt"));
		if (requireCommitted)
			AgentRecords.require(physical.disposition().equals("COMMITTED") && execution.accepted(),
					"Unaccepted Workflow output");
	}

	/**
	 * Finalize a fully assembled row before the immutable save; failure evidence is
	 * retained.
	 */
	public static Row assess(Execution execution, PreparedItem prepared, List<Invocation> invocations, Domain measured,
			JudgeRecording.ItemJuryFactory factory) {
		AgentRecords.require(execution.identity().equals(prepared.identity()), "Prepared/Workflow row mismatch");
		for (Invocation invocation : invocations) {
			AgentRecords.require(invocation.attempt().row().equals(prepared.identity()), "Prepared/row mismatch");
			validateAttempt(execution, invocation.attempt(), execution.accepted());
		}
		List<WorkflowProof> proof = List.of(new WorkflowProof(prepared.identity(), execution.snapshot().runId(),
				execution.snapshot().status().name(), execution.lifecycle(), execution.snapshotFile(),
				invocations.stream().map(Invocation::attempt).toList()));
		if (!execution.accepted())
			return new Row(prepared.identity(), invocations, execution.snapshot().status().name(), measured, List.of(),
					"NOT_ASSESSED", "Workflow did not produce an accepted result",
					execution.lifecycle().equals("DRAINED") ? "RETAINED" : "QUARANTINED", proof);
		return JudgeRecording.assess(prepared, invocations, execution.snapshot().status().name(), measured, factory,
				proof);
	}

}
