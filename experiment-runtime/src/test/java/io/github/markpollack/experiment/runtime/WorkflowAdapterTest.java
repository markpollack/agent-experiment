package io.github.markpollack.experiment.runtime;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.flows.compiler.*;
import io.github.markpollack.workflow.batch.durable.*;
import io.github.markpollack.experiment.runtime.AgentRecords.*;

class WorkflowAdapterTest {

	@TempDir
	Path temp;

	public record Request(Identity identity) {
	}

	public static final class Invoke implements Step<Request, Invocation> {

		public Invocation execute(StepContext c, Request r) {
			return new Invocation(
					new Attempt(r.identity(), "acq-" + c.attemptId(), c.runId(), c.invocationId(), c.attemptId(),
							c.attemptNumber()),
					"FAILED", "UNKNOWN", List.of(), "MISSING", List.of(), List.of(), "NOT_ATTEMPTED", List.of(),
					List.of(Measurement.unknown("cost", "USD", "not reported", "invocation")), "", "failure", 1000, 1);
		}

	}

	private static ExecutionCompatibility deployment() {
		return new ExecutionCompatibility("test", "exact-test-build", Map.of());
	}

	@Test
	void realReleasedRuntimePersistsBridgeAndValidatesAttempt() throws Exception {
		var invoke = new Invoke();
		var graph = Workflows.define("test").then(invoke).terminate(WorkflowModel.Terminal.SUCCEEDED).build();
		var registry = StepRegistry.builder().register("invoke-v1", invoke).build();
		var id = Identity.of("run", "item", "variant", 0);
		var execution = new WorkflowAdapter().execute(temp.resolve("fresh"), graph, registry, deployment(), id,
				new Request(id), Duration.ofSeconds(1), () -> {
				});
		assertThat(execution.accepted()).isTrue();
		var result = (Invocation) execution.value();
		assertThat(result.responseState()).isEqualTo("FAILED");
		assertThat(result.usage().getFirst().values()).isEmpty();
		WorkflowAdapter.validateAttempt(execution, result.attempt(), true);
		var a = result.attempt();
		assertThatThrownBy(() -> WorkflowAdapter.validateAttempt(execution,
				new Attempt(id, a.invocationId(), a.workflowRunId(), a.workflowInvocationId(), "wrong", 1), true))
			.hasMessageContaining("physical attempt");
		try (var reopened = DurableWorkflows.open(temp.resolve("fresh/workflow"), registry, deployment(),
				new ExecutionPolicy(1, 32, 10000, 1))) {
			assertThat(reopened.result(execution.snapshot().runId(), graph)).isEqualTo(result);
		}
	}

	public record Bad(Path path) {
	}

	public static final class BadStep implements Step<Bad, String> {

		public String execute(StepContext c, Bad b) {
			throw new AssertionError("must never execute");
		}

	}

	@Test
	void unsupportedDurableTypeRefusesBeforeProvider() {
		assertThatThrownBy(
				() -> Workflows.define("bad").then(new BadStep()).terminate(WorkflowModel.Terminal.SUCCEEDED).build())
			.isInstanceOf(IllegalArgumentException.class);
	}

	public static final class Slow implements Step<String, String> {

		final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);

		public String execute(StepContext c, String in) {
			entered.countDown();
			try {
				release.await();
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return "late";
		}

	}

	public static final class Successor implements Step<String, Integer> {

		final AtomicInteger calls = new AtomicInteger();

		public Integer execute(StepContext c, String s) {
			calls.incrementAndGet();
			return 1;
		}

	}

	@Test
	void deadlineQuarantinesUnsettledWorkAndNeverRunsSuccessor() throws Exception {
		var slow = new Slow();
		var next = new Successor();
		var graph = Workflows.define("slow")
			.maxDuration(Duration.ofMillis(500))
			.then(slow)
			.then(next)
			.terminate(WorkflowModel.Terminal.SUCCEEDED)
			.build();
		var registry = StepRegistry.builder().register("slow-v1", slow).register("next-v1", next).build();
		var terminated = new AtomicInteger();
		try {
			var execution = new WorkflowAdapter().execute(temp.resolve("slow"), graph, registry, deployment(),
					Identity.of("run", "item", "simple", 0), "input", Duration.ofMillis(30),
					terminated::incrementAndGet);
			assertThat(execution.accepted()).isFalse();
			assertThat(execution.lifecycle()).isEqualTo("QUARANTINED");
			assertThat(next.calls).hasValue(0);
			assertThat(slow.entered.getCount()).isZero();
		}
		finally {
			slow.release.countDown();
		}
	}

}
