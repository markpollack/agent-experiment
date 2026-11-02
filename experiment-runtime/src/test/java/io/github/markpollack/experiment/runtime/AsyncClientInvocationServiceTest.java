package io.github.markpollack.experiment.runtime;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.client.AgentExecution;
import io.github.markpollack.agents.client.AgentExecutionException;
import io.github.markpollack.agents.client.AgentExecutionProvider;
import io.github.markpollack.agents.client.ExecutionOutcome;
import io.github.markpollack.agents.model.AgentApi;
import io.github.markpollack.agents.model.AgentGeneration;
import io.github.markpollack.agents.model.AgentGenerationMetadata;
import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentTaskRequest;
import io.github.markpollack.experiment.runtime.AgentRecords.Attempt;
import io.github.markpollack.experiment.runtime.AgentRecords.Identity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

class AsyncClientInvocationServiceTest {

	@TempDir
	Path workspace;

	private static Attempt attempt(String id) {
		return new Attempt(Identity.of("run", "item", "variant", 0), id, "workflow", "logical", "physical", 1);
	}

	private static final class Provider implements AgentExecutionProvider {

		final AtomicInteger calls = new AtomicInteger();

		final AtomicInteger closes = new AtomicInteger();

		final CountDownLatch entered = new CountDownLatch(1);

		final CountDownLatch release = new CountDownLatch(1);

		boolean fails;

		public Connection open(io.github.markpollack.agents.model.AgentOptions options) {
			return new Connection() {
				public Turn prepare(String id) {
					return new Turn() {
						public AgentResponse call(AgentTaskRequest request) {
							calls.incrementAndGet();
							entered.countDown();
							try {
								if (!release.await(5, TimeUnit.SECONDS))
									throw new IllegalStateException("test release absent");
							}
							catch (InterruptedException e) {
								Thread.currentThread().interrupt();
								throw new CancellationException();
							}
							if (fails)
								throw new IllegalStateException("provider failure");
							return new AgentResponse(List.of(new AgentGeneration(request.goal(),
									new AgentGenerationMetadata("SUCCESS", Map.of()))));
						}

						public void stop(boolean interrupt) {
							release.countDown();
						}
					};
				}

				public void close() {
					closes.incrementAndGet();
					release.countDown();
				}
			};
		}

	}

	@Test
	void associatesRealExecutionAndOriginalDecisionWithoutTakingClientOwnership() throws Exception {
		var provider = new Provider();
		provider.release.countDown();
		try (var client = AgentClient.create(provider)) {
			var a = attempt("invocation");
			var started = new AsyncClientInvocationService(client).start(a, workspace, "response",
					Duration.ofSeconds(5), Optional.of("definition"));
			assertThat(started.attempt()).isSameAs(a);
			assertThat(started.execution().result().getResult()).isEqualTo("response");
			assertThat(started.decision().orElseThrow()).isSameAs(started.execution().decision().orElseThrow());
			var correlation = started.decision().orElseThrow().correlation();
			assertThat(correlation.experimentId()).contains("definition");
			assertThat(correlation.experimentRunId()).contains("run");
			assertThat(correlation.experimentInvocationId()).contains("invocation");
			assertThat(correlation.workflowRunId()).contains("workflow");
			assertThat(correlation.workflowInvocationId()).contains("logical");
			assertThat(correlation.workflowAttemptId()).contains("physical");
			assertThat(started.execution().awaitTermination(Duration.ofSeconds(2)).cleanupConfirmed()).isTrue();
			assertThat(client.goal("caller still owns client").runAsync().result().getResult())
				.isEqualTo("caller still owns client");
		}
		assertThat(provider.calls).hasValue(2);
		assertThat(provider.closes).hasValue(2);
	}

	@Test
	void concurrentDuplicateSelectionDispatchesOnce() throws Exception {
		var provider = new Provider();
		provider.release.countDown();
		try (var client = AgentClient.create(provider); var threads = Executors.newFixedThreadPool(2)) {
			var service = new AsyncClientInvocationService(client);
			var barrier = new CountDownLatch(1);
			var admitted = new AtomicInteger();
			var refused = new AtomicInteger();
			java.util.concurrent.Callable<Void> launch = () -> {
				barrier.await();
				try {
					service.start(attempt("shared"), workspace, "once", Duration.ofSeconds(5)).execution().result();
					admitted.incrementAndGet();
				}
				catch (IllegalStateException expected) {
					assertThat(expected).hasMessageContaining("already selected");
					refused.incrementAndGet();
				}
				return null;
			};
			var first = threads.submit(launch);
			var second = threads.submit(launch);
			barrier.countDown();
			first.get(5, TimeUnit.SECONDS);
			second.get(5, TimeUnit.SECONDS);
			assertThat(admitted).hasValue(1);
			assertThat(refused).hasValue(1);
		}
		assertThat(provider.calls).hasValue(1);
	}

	@Test
	void waiterTimeoutDoesNotCancelOrReplayExecution() throws Exception {
		var provider = new Provider();
		try (var client = AgentClient.create(provider)) {
			var started = new AsyncClientInvocationService(client).start(attempt("wait"), workspace, "later",
					Duration.ofSeconds(5));
			assertThat(provider.entered.await(2, TimeUnit.SECONDS)).isTrue();
			assertThat(started.decision()).isEmpty();
			assertThatThrownBy(() -> started.execution().result(Duration.ofMillis(5)))
				.isInstanceOf(TimeoutException.class);
			assertThat(started.decision()).isEmpty();
			provider.release.countDown();
			assertThat(started.execution().result().getResult()).isEqualTo("later");
			assertThat(started.decision().orElseThrow().outcome()).isInstanceOf(ExecutionOutcome.Success.class);
		}
		assertThat(provider.calls).hasValue(1);
	}

	@Test
	void typedProviderFailureRemainsOriginalClientFailure() throws Exception {
		var provider = new Provider();
		provider.fails = true;
		provider.release.countDown();
		try (var client = AgentClient.create(provider)) {
			var started = new AsyncClientInvocationService(client).start(attempt("failed"), workspace, "goal",
					Duration.ofSeconds(5));
			var failure = catchThrowableOfType(AgentExecutionException.class, () -> started.execution().result());
			assertThat(failure).isNotNull();
			var outcome = (ExecutionOutcome.Failed) started.decision().orElseThrow().outcome();
			assertThat(outcome.failure()).isSameAs(failure.failure());
			assertThat(started.decision().orElseThrow()).isSameAs(started.execution().decision().orElseThrow());
		}
	}

	@Test
	void cancellationKeepsActualElectedDecision() throws Exception {
		var provider = new Provider();
		try (var client = AgentClient.create(provider)) {
			var started = new AsyncClientInvocationService(client).start(attempt("cancel"), workspace, "goal",
					Duration.ofSeconds(5));
			assertThat(provider.entered.await(2, TimeUnit.SECONDS)).isTrue();
			assertThat(started.execution().cancel(true)).isTrue();
			assertThatThrownBy(() -> started.execution().result()).isInstanceOf(CancellationException.class);
			assertThat(started.decision().orElseThrow().outcome()).isInstanceOf(ExecutionOutcome.Cancelled.class);
			assertThat(started.execution().awaitTermination(Duration.ofSeconds(2)).cleanupConfirmed()).isTrue();
		}
	}

	@Test
	void invalidArgumentsDoNotReserveOrCallProvider() throws Exception {
		var provider = new Provider();
		provider.release.countDown();
		try (var client = AgentClient.create(provider)) {
			var service = new AsyncClientInvocationService(client);
			var a = attempt("validated");
			assertThatThrownBy(() -> service.start(a, workspace, " ", Duration.ofSeconds(5)))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> service.start(a, workspace, "p", Duration.ZERO))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> service.start(a, workspace.resolve("absent"), "p", Duration.ofSeconds(5)))
				.isInstanceOf(java.io.IOException.class);
			assertThatThrownBy(() -> service.start(a, workspace, "p", Duration.ofSeconds(5), Optional.of(" ")))
				.isInstanceOf(IllegalArgumentException.class);
			assertThat(provider.calls).hasValue(0);
			service.start(a, workspace, "valid", Duration.ofSeconds(5)).execution().result();
		}
		assertThat(provider.calls).hasValue(1);
	}

	@Test
	void unsupportedLegacyProviderHasNoBlockingFallbackAndAdmissionStaysReserved() throws Exception {
		var calls = new AtomicInteger();
		AgentApi legacy = request -> {
			calls.incrementAndGet();
			throw new AssertionError("Blocking fallback used");
		};
		try (var client = AgentClient.create(legacy)) {
			var service = new AsyncClientInvocationService(client);
			assertThatThrownBy(() -> service.start(attempt("legacy"), workspace, "p", Duration.ofSeconds(5)))
				.isInstanceOf(UnsupportedOperationException.class);
			assertThatThrownBy(() -> service.start(attempt("legacy"), workspace, "p", Duration.ofSeconds(5)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("already selected");
		}
		assertThat(calls).hasValue(0);
	}

	@Test
	void unusableHandleAndMismatchedDecisionCannotPermitReplay() throws Exception {
		var admissions = new AtomicInteger();
		var service = new AsyncClientInvocationService(returning(null, admissions));
		assertThatThrownBy(() -> service.start(attempt("null"), workspace, "p", Duration.ofSeconds(5)))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> service.start(attempt("null"), workspace, "p", Duration.ofSeconds(5)))
			.hasMessageContaining("already selected");
		assertThat(admissions).hasValue(1);
		var provider = new Provider();
		provider.release.countDown();
		try (var client = AgentClient.create(provider)) {
			var unrelated = client.goal("unassociated").runAsync();
			unrelated.result();
			var mismatched = new AsyncClientInvocationService(returning(unrelated, admissions));
			assertThatThrownBy(() -> mismatched.start(attempt("wrong"), workspace, "p", Duration.ofSeconds(5)))
				.hasMessageContaining("association mismatch");
			assertThatThrownBy(() -> mismatched.start(attempt("wrong"), workspace, "p", Duration.ofSeconds(5)))
				.hasMessageContaining("already selected");
		}
		assertThat(admissions).hasValue(2);
	}

	/**
	 * Invalid public interface boundary only; successful lifecycle tests use actual
	 * Client.
	 */
	private static AgentClient returning(AgentExecution execution, AtomicInteger admissions) {
		Object request = Proxy.newProxyInstance(AgentClient.class.getClassLoader(),
				new Class<?>[] { AgentClient.AgentClientRequestSpec.class }, (proxy, method, args) -> {
					if (method.getName().equals("runAsync")) {
						admissions.incrementAndGet();
						return execution;
					}
					return proxy;
				});
		return (AgentClient) Proxy.newProxyInstance(AgentClient.class.getClassLoader(),
				new Class<?>[] { AgentClient.class }, (proxy, method, args) -> {
					if (method.getName().equals("goal"))
						return request;
					throw new AssertionError("Unexpected Client call " + method.getName());
				});
	}

}
