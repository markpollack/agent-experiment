package io.github.markpollack.experiment.runtime;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import io.github.markpollack.experiment.runtime.AgentRecords.*;
import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel;
import io.github.markpollack.workflow.batch.durable.*;
import io.github.markpollack.journal.tck.ocsf.PackageReader;

/**
 * Opt-in bounded source check. Run explicitly with a new private
 * experiment.qualification.directory and the observed
 * experiment.qualification.cliVersion. This is one sentinel Read, not a domain pilot.
 * Never runs in the ordinary test suite.
 */
public class QualifiedSourceIT {

	public static final class Invoke implements Step<PreparedItem, Invocation> {

		private final ClaudeInvocationService service;

		private final Path directory;

		Invoke(ClaudeInvocationService service, Path directory) {
			this.service = service;
			this.directory = directory;
		}

		public Invocation execute(StepContext context, PreparedItem item) {
			return service
				.invoke(new ClaudeInvocationService.Request(item,
						"Use the Read tool exactly once to read " + directory.resolve("sentinel.txt")
								+ ". Reply with its text. Do not use other tools or change any files.",
						"claude-sonnet-4-6", 60_000, directory.resolve("attempt").toString(), true), context)
				.invocation();
		}

	}

	@Test
	void actualClientQualificationAssemblyAndImmutableResult() throws Exception {
		Path root = Path.of(Objects.requireNonNull(System.getProperty("experiment.qualification.directory"),
				"explicit private directory required"))
			.toAbsolutePath();
		String version = Objects.requireNonNull(System.getProperty("experiment.qualification.cliVersion"),
				"observed CLI version required");
		Files.createDirectory(root);
		Files.writeString(root.resolve("sentinel.txt"), "qualification-sentinel-" + UUID.randomUUID());
		var identity = Identity.of("qualification-" + UUID.randomUUID(), "sentinel-read", "fixture", 0);
		var prepared = new PreparedItem(identity, root.toString(), List.of(), List.of());
		var service = new ClaudeInvocationService(version, Path.of(System.getProperty("user.home"), ".claude/projects"),
				Duration.ofSeconds(5), AcquisitionAssembly.journal());
		var invoke = new Invoke(service, root);
		var graph = Workflows.define("qualified-source-smoke")
			.maxDuration(Duration.ofSeconds(90))
			.then(invoke)
			.terminate(WorkflowModel.Terminal.SUCCEEDED)
			.build();
		var steps = StepRegistry.builder().register("qualified-client-invoke", invoke).build();
		var compatibility = new ExecutionCompatibility("qualified-source-smoke", "committed-task-artifacts", Map.of());
		var execution = new WorkflowAdapter().execute(root.resolve("workflow"), graph, steps, compatibility, identity,
				prepared, Duration.ofSeconds(5), service::terminateActive);
		assertThat(execution.accepted()).as(execution.snapshot().toString()).isTrue();
		var invocation = (Invocation) execution.value();
		// Retain even a failed apparatus result before asserting qualification success.
		var proof = new WorkflowProof(identity, execution.snapshot().runId(), execution.snapshot().status().name(),
				execution.lifecycle(), execution.snapshotFile(), List.of(invocation.attempt()));
		var row = new Row(identity, List.of(invocation), execution.snapshot().status().name(),
				new Domain(List.of(), List.of()), List.of(), "NOT_ASSESSED", "sentinel smoke has no domain Jury",
				"RETAINED", List.of(proof));
		var result = new Result(AgentRecords.CURRENT_SCHEMA_VERSION, "AGENT_CANONICAL", identity.runId(),
				"qualified-source-smoke", System.currentTimeMillis(), List.of(row), List.of());
		new FileSystemResultStore(root.resolve("results")).save(result);
		RuntimeJson.mapper().writerWithDefaultPrettyPrinter().writeValue(root.resolve("result.json").toFile(), result);
		assertThat(invocation.assemblyState()).as(invocation.error()).isEqualTo("ASSEMBLED");
		Evidence.verify(row);
		assertThat(invocation.qualifications().getFirst().state()).isEqualTo("QUALIFIED");
		var packageView = PackageReader.open(Path.of(invocation.canonical().getFirst().events().path()));
		assertThat(packageView.refused()).isFalse();
		assertThat(packageView.events("tool_call", 1)).hasSize(1);
		assertThat(new FileSystemResultStore(root.resolve("results")).mostRecent()).contains(result);
		// Independent packaged reader verification is a separate process, using the
		// recorded q paths.
	}

}
