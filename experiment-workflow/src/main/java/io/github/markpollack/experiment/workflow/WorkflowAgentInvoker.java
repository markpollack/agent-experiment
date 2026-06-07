package io.github.markpollack.experiment.workflow;

import java.nio.file.Path;
import java.util.List;

import io.github.markpollack.agents.claude.ClaudeAgentModel;
import io.github.markpollack.agents.model.AgentApi;
import io.github.markpollack.experiment.agent.InvocationContext;
import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.storage.JsonFileStorage;
import io.github.markpollack.workflow.core.AgentContext;
import io.github.markpollack.workflow.flows.steps.AgentClient;
import io.github.markpollack.workflow.flows.steps.AgentClientStep;
import io.github.markpollack.workflow.flows.workflow.LocalStepRunner;
import io.github.markpollack.workflow.flows.workflow.Workflow;
import io.github.markpollack.workflow.flows.workflow.WorkflowExecutor;
import io.github.markpollack.workflow.journal.WorkflowJournal;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AgentInvoker that uses agent-workflow's {@link Workflow} to orchestrate a single
 * {@link AgentClientStep} backed by an {@link AgentApi}-compatible model.
 *
 * <p>
 * By default, uses {@link ClaudeAgentModel} configured with the invocation context's
 * workspace directory and a standard trace output path. Pass an {@link AgentApi}
 * implementation to the constructor overload to use a different model — the caller then
 * owns model lifecycle and configuration.
 *
 * <p>
 * Produces JSONL trace files per step and wires trace paths through the workflow journal.
 * This is the recommended invoker for experiments that need tool-call traces (Markov
 * analysis, cost attribution, debugging).
 *
 * <h2>Default (Claude) usage</h2> <pre>{@code
 * // Journal storage must be configured once at startup
 * Journal.configure(new JsonFileStorage(Path.of("experiments", "traces", ".agent-journal")));
 * WorkflowJournal.registerEventType();
 *
 * AgentInvoker invoker = new WorkflowAgentInvoker();
 * }</pre>
 *
 * <h2>Custom model usage</h2> <pre>{@code
 * AgentApi myModel = request -> myClient.execute(request);
 * AgentInvoker invoker = new WorkflowAgentInvoker(myModel);
 * }</pre>
 *
 * @see WorkflowInvoker
 * @see AbstractTemplateAgentInvoker
 */
public class WorkflowAgentInvoker extends AbstractTemplateAgentInvoker {

	private static final Logger logger = LoggerFactory.getLogger(WorkflowAgentInvoker.class);

	private static final Path TRACE_DIR = Path.of("experiments", "traces");

	private static final Path JOURNAL_DIR = Path.of("experiments", "traces", ".agent-journal");

	static {
		Journal.configure(new JsonFileStorage(JOURNAL_DIR));
		WorkflowJournal.registerEventType();
	}

	@Nullable
	private final AgentApi agentApi;

	/** Default constructor — uses {@link ClaudeAgentModel} configured per-invocation. */
	public WorkflowAgentInvoker() {
		super();
		this.agentApi = null;
	}

	/**
	 * Constructor with knowledge-file staging and default {@link ClaudeAgentModel}.
	 * @param knowledgeSourceDir directory containing knowledge files to stage
	 * @param knowledgeFiles list of relative paths to copy, or {@code ["index.md"]} to
	 * copy the full tree
	 */
	public WorkflowAgentInvoker(@Nullable Path knowledgeSourceDir, @Nullable List<String> knowledgeFiles) {
		super(knowledgeSourceDir, knowledgeFiles);
		this.agentApi = null;
	}

	/**
	 * Constructor with an injectable {@link AgentApi} model, for use with non-Claude
	 * models or when the caller owns model lifecycle.
	 * @param agentApi the model to use; the caller is responsible for its lifecycle
	 */
	public WorkflowAgentInvoker(AgentApi agentApi) {
		super();
		this.agentApi = agentApi;
	}

	/**
	 * Constructor with an injectable model and knowledge-file staging.
	 * @param agentApi the model to use; the caller is responsible for its lifecycle
	 * @param knowledgeSourceDir directory containing knowledge files to stage
	 * @param knowledgeFiles list of relative paths to copy, or {@code ["index.md"]} to
	 * copy the full tree
	 */
	public WorkflowAgentInvoker(AgentApi agentApi, @Nullable Path knowledgeSourceDir,
			@Nullable List<String> knowledgeFiles) {
		super(knowledgeSourceDir, knowledgeFiles);
		this.agentApi = agentApi;
	}

	@Override
	protected AgentResult invokeAgent(InvocationContext context) throws Exception {
		String experimentId = context.metadata().getOrDefault("experimentId", "experiment-run");
		logger.info("WorkflowAgentInvoker: executing single-step workflow for workspace: {}", context.workspacePath());

		try (Run run = Journal.run(experimentId).start()) {
			WorkflowExecutor executor = new WorkflowExecutor(new LocalStepRunner(), WorkflowJournal.forRun(run));

			if (agentApi != null) {
				runSingleStepWorkflow(context, executor,
						io.github.markpollack.agents.client.AgentClient.create(agentApi));
			}
			else {
				try (ClaudeAgentModel model = ClaudeAgentModel.builder()
					.workingDirectory(context.workspacePath())
					.traceDir(TRACE_DIR)
					.build()) {
					runSingleStepWorkflow(context, executor,
							io.github.markpollack.agents.client.AgentClient.create(model));
				}
			}
		}

		return new AgentResult(List.of(), null);
	}

	private void runSingleStepWorkflow(InvocationContext context, WorkflowExecutor executor,
			io.github.markpollack.agents.client.AgentClient coreClient) {

		AgentClient workflowClient = new AgentClient() {
			@Override
			public String execute(String prompt, AgentContext ctx) {
				return executeForResult(prompt, ctx).text();
			}

			@Override
			public ExecutionResult executeForResult(String prompt, AgentContext ctx) {
				var response = coreClient.run(prompt);
				String tracePath = (String) response.getMetadata().get("tracePath");
				return new ExecutionResult(response.getResult(), tracePath);
			}
		};

		AgentClientStep agentStep = AgentClientStep.of(workflowClient, "{input}");

		String result = Workflow.<String, String>define("experiment-run")
			.withExecutor(executor)
			.step(agentStep)
			.run(context.prompt());

		logger.info("Workflow completed, response length: {} chars", result.length());
	}

}
