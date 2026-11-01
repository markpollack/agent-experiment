package io.github.markpollack.experiment.runtime;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import io.github.markpollack.experiment.runtime.AgentRecords.*;
import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel;
import io.github.markpollack.workflow.batch.durable.*;
import io.github.markpollack.judge.verdict.Verdict;
import io.github.markpollack.judge.judgment.Judgment;

/**
 * Compiling consumer pattern. Domain functions and provider below are deterministic
 * fixtures, not a live pilot.
 */
public class ThreeStageConsumerExampleTest {

	@TempDir
	Path temp;

	public record Task(Identity identity, String workspace, String prompt, String model, long providerBudgetMs,
			String attemptRoot) {
	}

	public record Prepared(Task task, PreparedItem item, String expandedPrompt) {
	}

	public record Invoked(Prepared prepared, ClaudeInvocationService.Outcome outcome) {
	}

	public record Measured(Invoked invoked, Domain domain) {
	}

	public static final class Prepare implements Step<Task, Prepared> {

		private final Function<Task, Prepared> preparation;

		public Prepare(Function<Task, Prepared> preparation) {
			this.preparation = preparation;
		}

		public Prepared execute(StepContext context, Task task) {
			Prepared prepared = preparation.apply(task);
			if (!prepared.item().identity().equals(task.identity()))
				throw new IllegalArgumentException("Preparation changed row identity");
			return prepared;
		}

	}

	public static final class Invoke implements Step<Prepared, Invoked> {

		private final ClaudeInvocationService client;

		public Invoke(ClaudeInvocationService client) {
			this.client = client;
		}

		public Invoked execute(StepContext context, Prepared prepared) {
			Task task = prepared.task();
			var request = new ClaudeInvocationService.Request(prepared.item(), prepared.expandedPrompt(), task.model(),
					task.providerBudgetMs(), Path.of(task.attemptRoot()).resolve(context.attemptId()).toString(), true);
			return new Invoked(prepared, client.invoke(request, context));
		}

	}

	public static final class Measure implements Step<Invoked, Measured> {

		private final Function<Invoked, Domain> measurement;

		public Measure(Function<Invoked, Domain> measurement) {
			this.measurement = measurement;
		}

		public Measured execute(StepContext context, Invoked invoked) {
			return new Measured(invoked, measurement.apply(invoked));
		}

	}

	@Test
	void threeConcreteStagesThenPerItemJuryAndImmutableResult() throws Exception {
		var identity = Identity.of("example-run", "example-item", "simple", 0);
		Files.createDirectory(temp.resolve("attempts"));
		Task task = new Task(identity, temp.toString(), "fixture prompt", "fixture model", 5000,
				temp.resolve("attempts").toString());
		var client = new ClaudeInvocationService(
				(request, attempt, directory, budget) -> new ClaudeInvocationService.ProviderCall() {
					public ClaudeInvocationService.Delivery invoke() {
						return new ClaudeInvocationService.Delivery(
								"SUCCEEDED", "fixture response", List.of(), false, List.of(), List.of(Measurement
									.unknown("cost", "USD", "fixture has no measured usage", attempt.invocationId())),
								"");
					}

					public void close() {
					}
				}, Duration.ofMillis(100), AcquisitionAssembly.journal());
		var prepare = new Prepare(input -> new Prepared(input, new PreparedItem(input.identity(), input.workspace(),
				List.of(Measurement.measured("lineCoverage", "percent", 25, "fixture baseline", "item")), List.of()),
				input.prompt() + " baseline=25"));
		var invoke = new Invoke(client);
		var measure = new Measure(input -> new Domain(
				List.of(Measurement.measured("lineCoverage", "percent", 40, "fixture final", "item")), List.of()));
		var definition = Workflows.define("three-stage-example")
			.maxDuration(Duration.ofSeconds(20))
			.then("prepare-baseline", prepare)
			.then("invoke-agent", invoke)
			.then("measure-final", measure)
			.terminate(WorkflowModel.Terminal.SUCCEEDED)
			.build();
		var registry = StepRegistry.builder()
			.register("prepare-v1", prepare)
			.register("invoke-v1", invoke)
			.register("measure-v1", measure)
			.build();
		var deployment = new ExecutionCompatibility("consumer-example", "fixture-build",
				Map.of("providerBudgetMs", "5000"));
		var execution = new WorkflowAdapter().execute(temp.resolve("workflow"), definition, registry, deployment,
				identity, task, Duration.ofSeconds(1), client::terminateActive);
		assertThat(execution.accepted()).isTrue();
		Measured output = (Measured) execution.value();
		Row row = WorkflowAdapter.assess(execution, output.invoked().prepared().item(),
				List.of(output.invoked().outcome().invocation()), output.domain(), (prepared, domain) -> {
					assertThat(prepared.workspace()).isEqualTo(task.workspace());
					assertThat(prepared.baseline().getFirst().values()).containsExactly(25.0);
					assertThat(domain.measurements().getFirst().values()).containsExactly(40.0);
					return () -> Verdict.single("fixture",
							Judgment.fail("fixture domain fails despite successful orchestration"));
				});
		Result result = new Result(3, "AGENT_CANONICAL", identity.runId(), "fixture-example", 100, List.of(row),
				List.of());
		var store = new FileSystemResultStore(temp.resolve("results"));
		store.save(result);
		var reopened = new FileSystemResultStore(temp.resolve("results"));
		assertThat(reopened.load(identity.runId())).contains(result);
		assertThat(reopened.list()).containsExactly(result);
		assertThat(reopened.mostRecent()).contains(result);
		Evidence.verify(row);
		assertThat(row.verdicts().getFirst().conclusion()).isEqualTo("FAIL");
		assertThat(row.invocations().getFirst().captureState()).isEqualTo("MISSING");
		assertThat(row.workflowProof().getFirst().attempts()).containsExactly(row.invocations().getFirst().attempt());
		Files.createDirectories(Path.of("target/example"));
		RuntimeJson.mapper()
			.writerWithDefaultPrettyPrinter()
			.writeValue(Path.of("target/example/result.json").toFile(), result);
		// Mutation keeps the acquisition identity and row unchanged; the independent
		// Workflow proof catches it.
		var changed = RuntimeJson.mapper().valueToTree(result);
		((com.fasterxml.jackson.databind.node.ObjectNode) changed.at("/items/0/invocations/0/attempt"))
			.put("workflowAttemptId", "wrong");
		assertThatThrownBy(() -> RuntimeJson.mapper().treeToValue(changed, Result.class))
			.hasMessageContaining("Wrong persisted Workflow attempt");
		Path saved;
		try (var paths = Files.walk(temp.resolve("results"))) {
			saved = paths.filter(p -> p.getFileName().toString().equals("run.json")).findFirst().orElseThrow();
		}
		RuntimeJson.mapper().writeValue(saved.toFile(), changed);
		assertThatThrownBy(() -> reopened.load(identity.runId()))
			.hasMessageContaining("Wrong persisted Workflow attempt");
		assertThatThrownBy(reopened::list).hasMessageContaining("Wrong persisted Workflow attempt");
		assertThatThrownBy(reopened::mostRecent).hasMessageContaining("Wrong persisted Workflow attempt");
	}

}
