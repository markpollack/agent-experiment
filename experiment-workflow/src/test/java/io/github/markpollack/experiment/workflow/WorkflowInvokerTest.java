package io.github.markpollack.experiment.workflow;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import io.github.markpollack.experiment.agent.AgentInvocationException;
import io.github.markpollack.experiment.agent.InvocationContext;
import io.github.markpollack.experiment.agent.InvocationResult;
import io.github.markpollack.experiment.agent.TerminalStatus;
import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.storage.InMemoryStorage;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.workflow.Workflow;
import io.github.markpollack.workflow.flows.workflow.WorkflowExecutor;
import io.github.markpollack.workflow.journal.WorkflowJournal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WorkflowInvoker} — verifies journal Run lifecycle, cost/token
 * aggregation into {@link InvocationResult}, and error propagation. Uses in-memory
 * journal storage; no live LLM calls.
 */
class WorkflowInvokerTest {

	@TempDir
	Path tempDir;

	InMemoryStorage storage;

	@BeforeEach
	void setUp() {
		storage = new InMemoryStorage();
		Journal.configure(storage);
		WorkflowJournal.registerEventType();
	}

	@Test
	void invokeReturnsCompletedResult() throws AgentInvocationException {
		InvocationResult result = new SimpleWorkflowInvoker().invoke(context());

		assertThat(result.status()).isEqualTo(TerminalStatus.COMPLETED);
		assertThat(result.success()).isTrue();
		assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
	}

	@Test
	void invokeOpensJournalRunAndSetsSessionId() throws AgentInvocationException {
		InvocationResult result = new SimpleWorkflowInvoker().invoke(context());

		assertThat(result.sessionId()).isNotNull();
		assertThat(storage.listRuns(SimpleWorkflowInvoker.WORKFLOW_NAME)).hasSize(1);
	}

	@Test
	void invokeAggregatesCostFromStepTransitions() throws AgentInvocationException {
		// Deterministic steps contribute 0 tokens and 0 cost — verify accumulator
		// initialises and flows through without exception
		InvocationResult result = new SimpleWorkflowInvoker().invoke(context());

		assertThat(result.totalCostUsd()).isEqualTo(0.0);
		assertThat(result.outputTokens()).isEqualTo(0);
	}

	@Test
	void invokePreservesContextMetadataInResult() throws AgentInvocationException {
		var ctx = InvocationContext.builder()
			.workspacePath(tempDir)
			.prompt("test")
			.model("test-model")
			.timeout(Duration.ofSeconds(30))
			.metadata(Map.of("experimentId", "exp-123", "itemId", "item-1"))
			.build();

		InvocationResult result = new SimpleWorkflowInvoker().invoke(ctx);

		assertThat(result.metadata()).containsEntry("experimentId", "exp-123");
		assertThat(result.metadata()).containsEntry("itemId", "item-1");
	}

	@Test
	void enrichMetadataIsCalledAfterExecution() throws AgentInvocationException {
		InvocationResult result = new EnrichingWorkflowInvoker().invoke(context());

		assertThat(result.metadata()).containsEntry("enriched", "true");
	}

	@Test
	void invokeWrapsWorkflowExceptionAsAgentInvocationException() {
		assertThatThrownBy(() -> new FailingWorkflowInvoker().invoke(context()))
			.isInstanceOf(AgentInvocationException.class)
			.hasMessageContaining("Workflow execution failed");
	}

	@Test
	void multipleInvocationsCreateSeparateJournalRuns() throws AgentInvocationException {
		var invoker = new SimpleWorkflowInvoker();

		invoker.invoke(context());
		invoker.invoke(context());

		assertThat(storage.listRuns(SimpleWorkflowInvoker.WORKFLOW_NAME)).hasSize(2);
	}

	private InvocationContext context() {
		return InvocationContext.builder()
			.workspacePath(tempDir)
			.prompt("test prompt")
			.model("test-model")
			.timeout(Duration.ofSeconds(30))
			.build();
	}

	// --- Concrete test subclasses ---

	private static final class SimpleWorkflowInvoker extends WorkflowInvoker<String> {

		static final String WORKFLOW_NAME = "simple-test-workflow";

		@Override
		protected String workflowName() {
			return WORKFLOW_NAME;
		}

		@Override
		protected Workflow<Object, String> buildWorkflow(InvocationContext ctx, WorkflowExecutor executor) {
			Step<Object, String> step = Step.named("noop-step", (c, input) -> "output");
			return Workflow.<Object, String>define(workflowName()).withExecutor(executor).step(step).build();
		}

		@Override
		protected String buildInitialState(InvocationContext ctx) {
			return "initial";
		}

	}

	private static final class EnrichingWorkflowInvoker extends WorkflowInvoker<String> {

		@Override
		protected String workflowName() {
			return "enrichment-test";
		}

		@Override
		protected Workflow<Object, String> buildWorkflow(InvocationContext ctx, WorkflowExecutor executor) {
			return Workflow.<Object, String>define(workflowName())
				.withExecutor(executor)
				.step(Step.named("noop", (c, input) -> "output"))
				.build();
		}

		@Override
		protected String buildInitialState(InvocationContext ctx) {
			return "";
		}

		@Override
		protected void enrichMetadata(InvocationContext ctx, Map<String, String> metadata) {
			metadata.put("enriched", "true");
		}

	}

	private static final class FailingWorkflowInvoker extends WorkflowInvoker<String> {

		@Override
		protected String workflowName() {
			return "failing-workflow";
		}

		@Override
		protected Workflow<Object, String> buildWorkflow(InvocationContext ctx, WorkflowExecutor executor) {
			Step<Object, String> step = Step.named("fail", (c, input) -> {
				throw new RuntimeException("deliberate step failure");
			});
			return Workflow.<Object, String>define(workflowName()).withExecutor(executor).step(step).build();
		}

		@Override
		protected String buildInitialState(InvocationContext ctx) {
			return "";
		}

	}

}
