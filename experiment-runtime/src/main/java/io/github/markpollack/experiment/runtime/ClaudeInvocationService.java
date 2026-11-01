package io.github.markpollack.experiment.runtime;

import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import io.github.markpollack.agents.claude.*;
import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.workflow.flows.StepContext;
import io.github.markpollack.experiment.runtime.AgentRecords.*;

/**
 * Blocking Client acquisition with a bounded termination/drain and privately retained
 * late returns.
 */
public final class ClaudeInvocationService {

	public record Request(PreparedItem prepared, String prompt, String model, long providerBudgetMs,
			String attemptDirectory, boolean yolo) {
		public Request {
			Objects.requireNonNull(prepared);
			AgentRecords.text(prompt);
			AgentRecords.text(model);
			AgentRecords.text(attemptDirectory);
			AgentRecords.require(providerBudgetMs > 0, "Positive provider budget");
		}
	}

	public record Outcome(Invocation invocation, String workspaceState, String evidenceDirectory) {
	}

	public record Delivery(String responseState, String responseText, List<FileRef> acquisition, boolean complete,
			List<String> sessions, List<Measurement> usage, String error) {
	}

	/**
	 * Test/provider boundary; close requests supported termination, and must be safe
	 * during invoke.
	 */
	public interface ProviderCall extends AutoCloseable {

		Delivery invoke() throws Exception;

		@Override
		void close();

	}

	@FunctionalInterface
	public interface ProviderFactory {

		ProviderCall open(Request request, Attempt attempt, Path acquisitionDirectory, long actualBudgetMs);

	}

	private final ProviderFactory providers;

	private final AcquisitionAssembly assembly;

	private final Duration drain;

	private final Set<ProviderCall> active = ConcurrentHashMap.newKeySet();

	public ClaudeInvocationService(String cliVersion, Path nativeProjectsDirectory, Duration drain,
			AcquisitionAssembly assembly) {
		this((request, attempt, directory, budget) -> new ClaudeCall(request, attempt, directory, budget, cliVersion,
				nativeProjectsDirectory), drain, assembly);
	}

	public ClaudeInvocationService(ProviderFactory providers, Duration drain, AcquisitionAssembly assembly) {
		this.providers = Objects.requireNonNull(providers);
		this.assembly = Objects.requireNonNull(assembly);
		AgentRecords.require(drain.toMillis() > 0, "Positive drain bound");
		this.drain = drain;
	}

	public void terminateActive() {
		for (ProviderCall call : active)
			Thread.ofPlatform().daemon().name("experiment-client-stop").start(call::close);
	}

	public Outcome invoke(Request request, StepContext step) {
		Attempt attempt = new Attempt(request.prepared().identity(), UUID.randomUUID().toString(), step.runId(),
				step.invocationId(), step.attemptId(), step.attemptNumber());
		Path directory = Path.of(request.attemptDirectory()).toAbsolutePath().normalize();
		long budget = Math.min(request.providerBudgetMs(),
				Duration.between(Instant.now(), step.deadline()).toMillis() - drain.toMillis());
		AgentRecords.require(budget > 0, "No eligible provider budget after reserving drain");
		try {
			Files.createDirectory(directory);
		}
		catch (IOException e) {
			throw new java.io.UncheckedIOException("New attempt directory required", e);
		}
		long started = System.nanoTime();
		ProviderCall provider = providers.open(request, attempt, directory.resolve("acquired"), budget);
		active.add(provider);
		ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
		AtomicReference<Invocation> returned = new AtomicReference<>();
		Future<Invocation> future = executor.submit(() -> {
			Invocation invocation;
			try {
				invocation = delivery(attempt, provider.invoke(), budget, elapsed(started));
			}
			catch (Exception e) {
				invocation = unknown(attempt, "THREW", "MISSING", budget, elapsed(started), e.toString());
			}
			returned.set(invocation);
			// Retain before accepting the output; failure to retain is an invocation
			// failure, not success.
			Files.write(directory.resolve("delivery.json"), RuntimeJson.mapper().writeValueAsBytes(invocation),
					StandardOpenOption.CREATE_NEW);
			provider.close();
			active.remove(provider);
			return invocation;
		});
		executor.shutdown();
		Invocation invocation;
		boolean timedOut = false;
		boolean interrupted = false;
		try {
			invocation = future.get(budget, TimeUnit.MILLISECONDS);
			if (elapsed(started) > budget)
				timedOut = true;
		}
		catch (TimeoutException e) {
			timedOut = true;
			invocation = unknown(attempt, "TIMEOUT", "QUARANTINED", budget, elapsed(started),
					"Provider deadline; retained at " + directory);
		}
		catch (InterruptedException e) {
			interrupted = true;
			timedOut = true;
			invocation = unknown(attempt, "UNKNOWN", "QUARANTINED", budget, elapsed(started),
					"Caller interrupted; retained at " + directory);
		}
		catch (ExecutionException e) {
			invocation = returned.get();
			if (invocation == null)
				invocation = unknown(attempt, "THREW", "MISSING", budget, elapsed(started), e.toString());
			invocation = failure(invocation, "THREW", e.toString());
		}
		boolean drained = executor.isTerminated();
		if (!drained || timedOut) {
			Thread.ofPlatform().daemon().name("experiment-client-terminate").start(provider::close);
			try {
				drained = executor.awaitTermination(drain.toMillis(), TimeUnit.MILLISECONDS);
			}
			catch (InterruptedException e) {
				interrupted = true;
			}
		}
		if (timedOut) {
			Invocation late = returned.get();
			if (late != null)
				invocation = failure(late, "TIMEOUT", "Late return is retained but not accepted; " + directory);
			// No assembly and no accepted successor output when physical work is
			// unsettled.
		}
		else if (drained && invocation.responseState().matches("SUCCEEDED|FAILED")
				&& invocation.captureState().equals("COMPLETE")) {
			invocation = assembly.assemble(invocation, directory.resolve("canonical"));
		}
		if (drained)
			active.remove(provider);
		try {
			Files.write(directory.resolve("outcome.json"), RuntimeJson.mapper().writeValueAsBytes(invocation),
					StandardOpenOption.CREATE_NEW);
		}
		catch (IOException e) {
			throw new java.io.UncheckedIOException("Cannot retain invocation outcome", e);
		}
		if (interrupted)
			Thread.currentThread().interrupt();
		return new Outcome(invocation, drained ? "RETAINED" : "QUARANTINED", directory.toString());
	}

	private static long elapsed(long start) {
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
	}

	private static Invocation delivery(Attempt attempt, Delivery delivery, long budget, long elapsed)
			throws IOException {
		String process = "UNKNOWN";
		List<Integer> codes = List.of();
		if (!delivery.acquisition().isEmpty()) {
			JsonProcess p = process(
					AcquisitionAssembly.validateManifest(delivery.acquisition().getFirst(), attempt, false));
			process = p.state();
			codes = p.codes();
		}
		return new Invocation(attempt, delivery.responseState(), process, codes,
				delivery.acquisition().isEmpty() ? "MISSING" : delivery.complete() ? "COMPLETE" : "INCOMPLETE",
				delivery.acquisition(), List.of(), "NOT_ATTEMPTED", delivery.sessions(), delivery.usage(),
				delivery.responseText(), delivery.error(), budget, elapsed);
	}

	private record JsonProcess(String state, List<Integer> codes) {
	}

	private static JsonProcess process(com.fasterxml.jackson.databind.JsonNode manifest) {
		var p = manifest.path("process");
		var exit = p.path("exit_code");
		return new JsonProcess(p.path("status").asText("UNKNOWN"), exit.isInt() ? List.of(exit.intValue()) : List.of());
	}

	private static Invocation unknown(Attempt attempt, String state, String capture, long budget, long elapsed,
			String reason) {
		return new Invocation(attempt, state, "UNKNOWN", List.of(), capture, List.of(), List.of(), "NOT_ATTEMPTED",
				List.of(),
				List.of(Measurement.unknown("cost", "USD", "No qualified reported cost", attempt.invocationId()),
						Measurement.unknown("tokens", "token", "No qualified token total", attempt.invocationId())),
				"", reason, budget, elapsed);
	}

	private static Invocation failure(Invocation i, String response, String reason) {
		return new Invocation(i.attempt(), response, i.processState(), i.exitCodes(), i.captureState(),
				i.acquisitions(), List.of(), "NOT_ATTEMPTED", i.providerSessions(), i.usage(), i.responseText(), reason,
				i.providerBudgetMs(), i.elapsedMs());
	}

	private static final class ClaudeCall implements ProviderCall {

		private final ClaudeAgentModel model;

		private final Request request;

		private final Attempt attempt;

		ClaudeCall(Request request, Attempt attempt, Path directory, long budget, String cli, Path nativeRoot) {
			this.request = request;
			this.attempt = attempt;
			model = ClaudeAgentModel.builder()
				.workingDirectory(Path.of(request.prepared().workspace()))
				.timeout(Duration.ofMillis(budget))
				.defaultOptions(ClaudeAgentOptions.builder().model(request.model()).yolo(request.yolo()).build())
				.capture(new ClaudeCaptureOptions(directory, attempt.invocationId(), cli, nativeRoot,
						AcquisitionAssembly.provenance(attempt)))
				.build();
		}

		@Override
		public Delivery invoke() {
			var response = AgentClient.create(model)
				.goal(request.prompt())
				.workingDirectory(Path.of(request.prepared().workspace()))
				.run();
			ClaudeAcquisition acquired = ClaudeAcquisition.from(response.getAgentResponse());
			if (acquired != null && !acquired.invocationId().equals(attempt.invocationId()))
				throw new IllegalArgumentException("Client receipt invocation mismatch");
			var refs = acquired == null ? List.<FileRef>of()
					: List.of(new FileRef(acquired.manifest().toString(), acquired.sha256(), "acquisition-manifest"));
			var sessions = acquired == null || acquired.sessionId() == null ? List.<String>of()
					: List.of(acquired.sessionId());
			return new Delivery(response.isSuccessful() ? "SUCCEEDED" : "FAILED",
					Objects.toString(response.getResult(), ""), refs, acquired != null && acquired.complete(), sessions,
					List.of(Measurement.unknown("cost", "USD", "Use canonical scoped observations; no inferred total",
							attempt.invocationId()),
							Measurement.unknown("tokens", "token",
									"Use canonical scoped observations; no inferred total", attempt.invocationId())),
					"");
		}

		@Override
		public void close() {
			model.close();
		}

	}

}
