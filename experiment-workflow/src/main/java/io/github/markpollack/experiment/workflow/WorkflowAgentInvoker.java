package io.github.markpollack.experiment.workflow;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import io.github.markpollack.agents.model.AgentApi;
import io.github.markpollack.experiment.agent.*;
import io.github.markpollack.experiment.agent.client.AgentClientInvoker;
import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.batch.durable.*;
import org.jspecify.annotations.Nullable;

/**
 * One current typed Client step; knowledge hooks remain owned by the template adapter.
 */
public class WorkflowAgentInvoker extends AbstractTemplateAgentInvoker {

	private final AgentClientInvoker invoker;

	public WorkflowAgentInvoker() {
		this(null, null, null);
	}

	public WorkflowAgentInvoker(@Nullable Path knowledgeSourceDir, @Nullable List<String> knowledgeFiles) {
		this(null, knowledgeSourceDir, knowledgeFiles);
	}

	public WorkflowAgentInvoker(AgentApi api) {
		this(api, null, null);
	}

	public WorkflowAgentInvoker(@Nullable AgentApi api, @Nullable Path knowledgeSourceDir,
			@Nullable List<String> knowledgeFiles) {
		super(knowledgeSourceDir, knowledgeFiles);
		invoker = api == null ? new AgentClientInvoker() : new AgentClientInvoker(api);
	}

	public record Entry(String key, String value) {
	}

	public record Request(String workspace, String prompt, String model, long timeoutMs, List<Entry> metadata) {
	}

	public record Reply(boolean successful, String response, List<Entry> metadata) {
	}

	public static final class Invoke implements Step<Request, Reply> {

		private final AgentClientInvoker invoker;

		public Invoke(AgentClientInvoker invoker) {
			this.invoker = invoker;
		}

		public Reply execute(StepContext step, Request request) {
			try {
				var value = invoker.invoke(InvocationContext.builder()
					.workspacePath(Path.of(request.workspace()))
					.prompt(request.prompt())
					.model(request.model())
					.timeout(Duration.ofMillis(request.timeoutMs()))
					.metadata(request.metadata()
						.stream()
						.collect(java.util.stream.Collectors.toMap(Entry::key, Entry::value)))
					.build());
				return new Reply(value.success(), value.metadata().getOrDefault("agentResponse", ""),
						value.metadata()
							.entrySet()
							.stream()
							.map(entry -> new Entry(entry.getKey(), entry.getValue()))
							.toList());
			}
			catch (AgentInvocationException failure) {
				throw new IllegalStateException(failure);
			}
		}

	}

	/**
	 * Workflow build identity of this invoker: the Experiment build's own version. A
	 * Workflow run recorded by one Experiment version is refused as an incompatible
	 * deployment on another, so runs recorded under a {@code 0.10.0} snapshot are not
	 * resumable on {@code 1.0.0}. Finish or restart them on the build that started them.
	 */
	static final String BUILD_ID = io.github.markpollack.experiment.runtime.LoadedArtifacts.experimentVersion();

	@Override
	protected AgentResult invokeAgent(InvocationContext context) throws Exception {
		Invoke step = new Invoke(invoker);
		var definition = Workflows.define("experiment-client")
			.maxDuration(context.timeout())
			.then(step)
			.terminate(io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal.SUCCEEDED)
			.build();
		var plan = new WorkflowInvoker.Plan(definition, StepRegistry.builder().register("client-v1", step).build(),
				new ExecutionCompatibility("experiment-client", BUILD_ID, Map.of()));
		var driver = new WorkflowInvoker<Request>() {
			protected String workflowName() {
				return "experiment-client";
			}

			protected Plan buildWorkflow(InvocationContext ignored) {
				return plan;
			}

			protected Request buildInitialState(InvocationContext value) {
				return new Request(value.workspacePath().toString(), value.prompt(), value.model(),
						value.timeout().toMillis(),
						value.metadata()
							.entrySet()
							.stream()
							.map(entry -> new Entry(entry.getKey(), entry.getValue()))
							.toList());
			}

			protected boolean outputSucceeded(Object value) {
				return value instanceof Reply reply && reply.successful();
			}
		};
		return new AgentResult(driver.invoke(context));
	}

}
