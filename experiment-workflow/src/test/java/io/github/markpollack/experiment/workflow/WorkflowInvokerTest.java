package io.github.markpollack.experiment.workflow;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import io.github.markpollack.experiment.agent.*;
import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel;
import io.github.markpollack.workflow.batch.durable.*;
import io.github.markpollack.agents.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class WorkflowInvokerTest {

	@TempDir
	Path root;

	public static final class Echo implements Step<String, String> {

		public String execute(StepContext context, String value) {
			return value + " done";
		}

	}

	public static final class Fails implements Step<String, String> {

		public String execute(StepContext context, String value) {
			throw new IllegalStateException("fixture failed");
		}

	}

	private static final class Driver extends WorkflowInvoker<String> {

		private final Plan plan;

		Driver(boolean fail) {
			Step<String, String> step = fail ? new Fails() : new Echo();
			var graph = Workflows.define("current-test")
				.maxDuration(Duration.ofSeconds(5))
				.then(step)
				.terminate(WorkflowModel.Terminal.SUCCEEDED)
				.build();
			plan = new Plan(graph, StepRegistry.builder().register("step-v1", step).build(),
					new ExecutionCompatibility("fixture", "fixture-v1", Map.of()));
		}

		protected String workflowName() {
			return "current-test";
		}

		protected Plan buildWorkflow(InvocationContext context) {
			return plan;
		}

		protected String buildInitialState(InvocationContext context) {
			return context.prompt();
		}

		protected void enrichMetadata(InvocationContext context, Map<String, String> metadata) {
			metadata.put("enriched", "true");
		}

	}

	private InvocationContext context() {
		return InvocationContext.builder()
			.workspacePath(root)
			.prompt("hello")
			.model("fixture")
			.timeout(Duration.ofSeconds(5))
			.metadata(Map.of("itemId", "item"))
			.build();
	}

	@Test
	void typedWorkflowReturnsActualPersistedValue() throws Exception {
		var value = new Driver(false).invoke(context());
		assertThat(value.success()).isTrue();
		assertThat(value.metadata()).containsEntry("workflowResult", "\"hello done\"");
		assertThat(Path.of(value.metadata().get("workflowSnapshot"))).exists();
	}

	@Test
	void workflowCompletionDoesNotInventUsage() throws Exception {
		assertThat(new Driver(false).invoke(context()).metadata()).containsEntry("measurementState", "UNKNOWN");
	}

	@Test
	void contextMetadataAndHooksSurvive() throws Exception {
		assertThat(new Driver(false).invoke(context()).metadata()).containsEntry("itemId", "item")
			.containsEntry("enriched", "true");
	}

	@Test
	void eachInvocationGetsAFreshWorkflowAndSnapshot() throws Exception {
		var driver = new Driver(false);
		var one = driver.invoke(context());
		var two = driver.invoke(context());
		assertThat(one.sessionId()).isNotEqualTo(two.sessionId());
		assertThat(one.metadata().get("workflowSnapshot")).isNotEqualTo(two.metadata().get("workflowSnapshot"));
	}

	@Test
	void failingStepRetainsTerminalFailureWithoutRetry() throws Exception {
		var value = new Driver(true).invoke(context());
		assertThat(value.success()).isFalse();
		assertThat(value.metadata().get("workflowState")).isNotEqualTo("SUCCEEDED");
		assertThat(Path.of(value.metadata().get("workflowSnapshot"))).exists();
	}

	@Test
	void installedRuntimeReopensWithoutExecutingStep() throws Exception {
		var driver = new Driver(false);
		var value = driver.invoke(context());
		Path directory = Path.of(value.metadata().get("workflowSnapshot")).getParent().resolve("workflow");
		try (var reader = DurableWorkflows.open(directory, driver.plan.steps(), driver.plan.deployment(),
				new ExecutionPolicy(1, 32, 10000, 1))) {
			assertThat(reader.result(value.sessionId(), driver.plan.definition())).isEqualTo("hello done");
		}
	}

	@Test
	void clientWorkflowPreservesFailedResponseAsFailure() throws Exception {
		AgentApi fake = request -> new AgentResponse(
				List.of(new AgentGeneration("failed answer", new AgentGenerationMetadata("ERROR", Map.of()))));
		var value = new WorkflowAgentInvoker(fake).invoke(context());
		assertThat(value.success()).isFalse();
		assertThat(value.metadata()).containsEntry("measurementState", "UNKNOWN");
		assertThat(value.metadata().get("workflowResult")).contains("failed answer");
	}

}
