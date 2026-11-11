package io.github.markpollack.experiment.runtime;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel;
import io.github.markpollack.workflow.batch.durable.*;
import io.github.markpollack.experiment.runtime.AgentRecords.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

class UntimedWorkflowAdapterTest {

	@TempDir
	Path temp;

	public static final class Echo implements Step<String, String> {

		public String execute(StepContext context, String input) {
			assertThat(context.hasDeadline()).isFalse();
			return input;
		}

	}

	public static final class RetainedWork implements Step<String, String> {

		final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);

		final AtomicReference<StepContext> context = new AtomicReference<>();

		final Path partial;

		RetainedWork(Path partial) {
			this.partial = partial;
		}

		public String execute(StepContext step, String input) {
			context.set(step);
			try {
				Files.writeString(partial, "observed before cancellation", StandardOpenOption.CREATE_NEW);
				entered.countDown();
				release.await();
				return input;
			}
			catch (Exception failure) {
				throw new IllegalStateException(failure);
			}
		}

	}

	private static ExecutionCompatibility deployment() {
		return new ExecutionCompatibility("untimed-adapter-test", "fixture-build", Map.of());
	}

	@Test
	void explicitUntimedWorkflowReturnsAndReopensItsActualResult() throws Exception {
		var step = new Echo();
		var definition = Workflows.define("untimed-echo")
			.withoutDeadline()
			.then(step)
			.terminate(WorkflowModel.Terminal.SUCCEEDED)
			.build();
		var registry = StepRegistry.builder().register("echo-v1", step).build();
		Path fresh = temp.resolve("echo");
		var execution = new WorkflowAdapter().execute(fresh, definition, registry, deployment(),
				Identity.of("run", "item", "variant", 0), "actual output", Duration.ofSeconds(1), () -> {
					throw new AssertionError("successful work must not be terminated");
				});
		assertThat(execution.accepted()).isTrue();
		assertThat(execution.snapshot().hasDeadline()).isFalse();
		assertThat(execution.value()).isEqualTo("actual output");
		Evidence.verify(execution.snapshotFile());
		try (var reopened = DurableWorkflows.open(fresh.resolve("workflow"), registry, deployment(),
				new ExecutionPolicy(1, 32, 10000, 1))) {
			assertThat(reopened.inspect(execution.snapshot().runId()).hasDeadline()).isFalse();
			assertThat(reopened.result(execution.snapshot().runId(), definition)).isEqualTo("actual output");
		}
	}

	@Test
	void untimedCallerCancellationRetainsSnapshotAndPartialEvidenceBeforeRestoringInterrupt() throws Exception {
		cancelAndRetain(true, false);
	}

	@Test
	void finiteCallerCancellationRetainsSnapshotAndPartialEvidenceBeforeRestoringInterrupt() throws Exception {
		cancelAndRetain(false, false);
	}

	@Test
	void cooperativeUntimedCancellationDrainsWithinItsFiniteAllowance() throws Exception {
		cancelAndRetain(true, true);
	}

	private void cancelAndRetain(boolean untimed, boolean cooperative) throws Exception {
		Path partial = temp.resolve("partial.txt");
		var step = new RetainedWork(partial);
		var start = Workflows.define("cancel");
		var definition = (untimed ? start.withoutDeadline() : start.maxDuration(Duration.ofSeconds(10))).then(step)
			.terminate(WorkflowModel.Terminal.SUCCEEDED)
			.build();
		var registry = StepRegistry.builder().register("retained-v1", step).build();
		var id = Identity.of("cancel-run", "item", "variant", 0);
		var observed = new AtomicReference<WorkflowAdapter.Execution>();
		var failure = new AtomicReference<Throwable>();
		var interruptRestored = new AtomicBoolean();
		var terminationRequested = new CountDownLatch(1);
		var caller = new Thread(() -> {
			try {
				observed.set(new WorkflowAdapter().execute(temp.resolve("cancel"), definition, registry, deployment(),
						id, "input", cooperative ? Duration.ofSeconds(1) : Duration.ofMillis(100), () -> {
							terminationRequested.countDown();
							if (cooperative)
								step.release.countDown();
						}));
			}
			catch (Throwable thrown) {
				failure.set(thrown);
			}
			finally {
				interruptRestored.set(Thread.currentThread().isInterrupted());
			}
		}, "fixture-caller");
		try {
			caller.start();
			assertThat(step.entered.await(5, TimeUnit.SECONDS)).isTrue();
			caller.interrupt();
			caller.join(5000);
			assertThat(caller.isAlive()).isFalse();
			assertThat(failure.get()).isNull();
			assertThat(interruptRestored).isTrue();
			assertThat(terminationRequested.await(2, TimeUnit.SECONDS)).isTrue();
			var execution = observed.get();
			assertThat(execution.snapshot().status()).isEqualTo(RunSnapshot.Status.CANCELLED);
			assertThat(execution.snapshot().hasDeadline()).isEqualTo(!untimed);
			assertThat(execution.accepted()).isFalse();
			assertThat(execution.lifecycle()).isEqualTo(cooperative ? "DRAINED" : "QUARANTINED");
			Evidence.verify(execution.snapshotFile());
			assertThat(RuntimeJson.mapper()
				.readTree(Path.of(execution.snapshotFile().path()).toFile())
				.path("status")
				.asText()).isEqualTo("CANCELLED");
			assertThat(Files.readString(partial)).isEqualTo("observed before cancellation");
			var context = step.context.get();
			var attempt = new Attempt(id, "fixture-invocation", context.runId(), context.invocationId(),
					context.attemptId(), context.attemptNumber());
			var invocation = new Invocation(attempt, "UNKNOWN", "UNKNOWN", List.of(), "MISSING", List.of(), List.of(),
					"NOT_ATTEMPTED", List.of(),
					List.of(Measurement.unknown("cost", "USD", "cancelled before reported usage", "invocation")), "",
					"cancelled before observed response", untimed ? 0 : 10000, 1);
			var row = WorkflowAdapter.assess(execution, new PreparedItem(id, temp.toString(), List.of(), List.of()),
					List.of(invocation), new Domain(List.of(), List.of(Evidence.reference(partial, "partial-output"))),
					(p, d) -> {
						throw new AssertionError("cancelled work must not be assessed");
					});
			Path results = temp.resolve("results");
			new FileSystemResultStore(results).save(RuntimeStoreTest.result(id.runId(), List.of(row)));
			var read = new FileSystemResultStore(results).load(id.runId()).orElseThrow().items().getFirst();
			assertThat(read.workflowState()).isEqualTo("CANCELLED");
			assertThat(read.judgeState()).isEqualTo("NOT_ASSESSED");
			assertThat(read.invocations().getFirst().attempt()).isEqualTo(attempt);
			assertThat(read.invocations().getFirst().providerBudgetMs()).isEqualTo(untimed ? 0 : 10000);
			Evidence.verify(read);
		}
		finally {
			step.release.countDown();
			caller.join(5000);
		}
	}

}
