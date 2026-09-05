package io.github.markpollack.experiment.runner;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.github.markpollack.experiment.agent.AgentInvocationException;
import io.github.markpollack.experiment.agent.AgentInvoker;
import io.github.markpollack.experiment.result.RunConditions;
import io.github.markpollack.experiment.agent.InvocationContext;
import io.github.markpollack.experiment.agent.InvocationResult;
import io.github.markpollack.experiment.agent.TerminalStatus;
import io.github.markpollack.experiment.agent.ToolTelemetry;
import io.github.markpollack.experiment.dataset.Dataset;
import io.github.markpollack.experiment.dataset.DatasetItem;
import io.github.markpollack.experiment.dataset.DatasetManager;
import io.github.markpollack.experiment.dataset.DatasetVersion;
import io.github.markpollack.experiment.dataset.ResolvedItem;
import io.github.markpollack.experiment.journal.ExperimentJournal;
import io.github.markpollack.experiment.journal.RunJournal;
import io.github.markpollack.experiment.result.ExperimentResult;
import io.github.markpollack.experiment.result.ItemResult;
import io.github.markpollack.experiment.result.RecordedVerdict;
import io.github.markpollack.experiment.result.KnowledgeManifest;
import io.github.markpollack.experiment.runner.workspace.DefaultWorkspaceProvisioner;
import io.github.markpollack.experiment.runner.workspace.WorkspaceProvisioner;
import io.github.markpollack.experiment.scoring.JudgmentContextFactory;
import io.github.markpollack.experiment.util.GitOperations;
import io.github.markpollack.experiment.scoring.VerdictExtractor;
import io.github.markpollack.experiment.store.ActiveSession;
import io.github.markpollack.experiment.store.ResultStore;
import io.github.markpollack.experiment.store.SessionStore;
import io.github.markpollack.journal.claude.PhaseCapture;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.markpollack.judge.context.JudgmentContext;
import io.github.markpollack.judge.jury.Jury;
import io.github.markpollack.judge.jury.Verdict;

/**
 * Orchestrates a full agent experiment: load dataset, iterate items, invoke agent, judge
 * via Jury, aggregate, and persist results.
 *
 * <p>
 * Item failures do not halt the experiment — they produce failed {@link ItemResult}s.
 * Result store failures DO halt (thrown to caller).
 * </p>
 */
public class AgentExperiment {

	private static final Logger logger = LoggerFactory.getLogger(AgentExperiment.class);

	private final DatasetManager datasetManager;

	private final Jury jury;

	private final ResultStore resultStore;

	private final @Nullable SessionStore sessionStore;

	private final ExperimentConfig config;

	private final WorkspaceProvisioner workspaceProvisioner;

	public AgentExperiment(DatasetManager datasetManager, Jury jury, ResultStore resultStore, ExperimentConfig config) {
		this(datasetManager, jury, resultStore, null, config);
	}

	public AgentExperiment(DatasetManager datasetManager, Jury jury, ResultStore resultStore,
			@Nullable SessionStore sessionStore, ExperimentConfig config) {
		this(datasetManager, jury, resultStore, sessionStore, config, new DefaultWorkspaceProvisioner());
	}

	/**
	 * Full constructor allowing a custom {@link WorkspaceProvisioner} — e.g. a
	 * {@link io.github.markpollack.experiment.runner.workspace.GitWorkspaceProvisioner}
	 * to check out a dataset item's {@code beforeRef} from git. The provisioner is closed
	 * at the end of {@link #run(AgentInvoker, ActiveSession)}.
	 */
	public AgentExperiment(DatasetManager datasetManager, Jury jury, ResultStore resultStore,
			@Nullable SessionStore sessionStore, ExperimentConfig config, WorkspaceProvisioner workspaceProvisioner) {
		this.datasetManager = java.util.Objects.requireNonNull(datasetManager, "datasetManager must not be null");
		this.jury = java.util.Objects.requireNonNull(jury, "jury must not be null");
		this.resultStore = java.util.Objects.requireNonNull(resultStore, "resultStore must not be null");
		this.sessionStore = sessionStore;
		this.config = java.util.Objects.requireNonNull(config, "config must not be null");
		this.workspaceProvisioner = java.util.Objects.requireNonNull(workspaceProvisioner,
				"workspaceProvisioner must not be null");
	}

	/**
	 * Run the full experiment.
	 * @param agentInvoker the agent to evaluate
	 * @return the complete experiment result (also persisted via ResultStore)
	 */
	public ExperimentResult run(AgentInvoker agentInvoker) {
		return run(agentInvoker, null);
	}

	/**
	 * Run the full experiment within a session. When {@code activeSession} is non-null,
	 * traces and workspaces are written under the session directory, and the result is
	 * saved to the {@link SessionStore}.
	 * @param agentInvoker the agent to evaluate
	 * @param activeSession the session context, or null for non-session runs
	 * @return the complete experiment result (also persisted via ResultStore)
	 */
	public ExperimentResult run(AgentInvoker agentInvoker, @Nullable ActiveSession activeSession) {
		long startTime = System.currentTimeMillis();
		String experimentId = UUID.randomUUID().toString();

		// Compute run directory for artifacts (trace files, run log)
		@Nullable Path runDir = computeRunDir(activeSession, experimentId);

		logger.info("Starting experiment '{}' (id: {})", config.experimentName(), experimentId);

		// Attach run log if runDir available
		Object runLogHandle = runDir != null ? RunLogManager.attach(runDir) : null;

		try {
			return doRun(agentInvoker, startTime, experimentId, runDir, activeSession);
		}
		finally {
			workspaceProvisioner.close();
			RunLogManager.detach(runLogHandle);
		}
	}

	private @Nullable Path computeRunDir(@Nullable ActiveSession activeSession, String experimentId) {
		if (config.outputDir() == null) {
			return null;
		}
		if (activeSession != null) {
			return config.outputDir()
				.resolve(config.experimentName())
				.resolve("sessions")
				.resolve(activeSession.sessionName())
				.resolve(activeSession.variantName());
		}
		return config.outputDir().resolve(config.experimentName()).resolve(experimentId);
	}

	/**
	 * The conditions this run executed under, gathered at run time from the components
	 * that know them.
	 *
	 * <p>
	 * The framework's own governing parameters come from {@link ExperimentConfig}; the
	 * invoker's come from {@link AgentInvoker#declaredConditions()}, because an invoker's
	 * configuration does not otherwise cross into the result. An invoker that declares
	 * nothing leaves the run marked incomplete rather than looking like a run with no
	 * conditions, and {@code DefaultComparisonEngine} then refuses to compare it.
	 */
	private static RunConditions runConditions(AgentInvoker agentInvoker, ExperimentConfig config) {
		Map<String, String> framework = new java.util.TreeMap<>();
		framework.put("model", config.model());
		framework.put("perItemTimeout", config.perItemTimeout().toString());
		if (config.experimentTimeout() != null) {
			framework.put("experimentTimeout", config.experimentTimeout().toString());
		}
		framework.put("requireCleanGit", String.valueOf(config.requireCleanGit()));
		return RunConditions.undeclared("invoker")
			.declaring("config", framework)
			.declaring("invoker", agentInvoker.declaredConditions());
	}

	private ExperimentResult doRun(AgentInvoker agentInvoker, long startTime, String experimentId,
			@Nullable Path runDir, @Nullable ActiveSession activeSession) {
		// Capture experiment code git state for reproducibility
		Path projectRoot = config.projectRoot() != null ? config.projectRoot()
				: Path.of(System.getProperty("user.dir"));
		String codeVersion = GitOperations.resolveHead(projectRoot);
		List<String> dirtyFiles = GitOperations.dirtyFiles(projectRoot);
		boolean codeDirty = !dirtyFiles.isEmpty();
		if (codeDirty) {
			List<String> critical = GitOperations.criticalDirtyFiles(dirtyFiles);
			if (!critical.isEmpty()) {
				String message = "Experiment project has uncommitted changes in behavior-affecting paths: " + critical
						+ " — commit before running to ensure reproducibility.";
				if (config.requireCleanGit()) {
					throw new IllegalStateException(message);
				}
				logger.warn(message);
			}
			else {
				logger.info("Experiment running with uncommitted changes in non-critical paths: {} "
						+ "— dirty state recorded in result for auditing.", dirtyFiles);
			}
		}

		Dataset dataset = datasetManager.load(config.datasetDir());
		List<DatasetItem> items = config.itemFilter() != null
				? datasetManager.filteredItems(dataset, config.itemFilter()) : datasetManager.activeItems(dataset);
		DatasetVersion version = datasetManager.currentVersion(dataset);

		@Nullable KnowledgeManifest knowledgeManifest = config.knowledgeBaseDir() != null
				? KnowledgeManifest.snapshot(config.knowledgeBaseDir()) : null;

		logger.info("Loaded dataset '{}' v{} ({} items)", dataset.name(), version.semanticVersion(), items.size());

		ExperimentJournal experimentJournal = openExperimentJournal(runDir);

		List<ItemResult> results = new ArrayList<>();
		for (DatasetItem item : items) {
			ItemResult result = runItem(agentInvoker, item, experimentId, runDir, activeSession, experimentJournal);
			results.add(result);
			logger.info("Item {}: {} (passed={}, cost=${}, tokens={})", item.id(), item.slug(), result.passed(),
					String.format("%.4f", result.costUsd()), result.totalTokens());
		}

		long totalDurationMs = System.currentTimeMillis() - startTime;

		// Aggregate scores — mean per judge across items
		Map<String, Double> aggregateScores = aggregateScores(results);
		double passRate = results.isEmpty() ? 0.0
				: results.stream().filter(ItemResult::passed).count() / (double) results.size();
		double totalCost = results.stream().mapToDouble(ItemResult::costUsd).sum();
		int totalTokens = results.stream().mapToInt(ItemResult::totalTokens).sum();

		ExperimentResult experimentResult = ExperimentResult.builder()
			.experimentId(experimentId)
			.experimentName(config.experimentName())
			.datasetVersion(version.gitCommit())
			.datasetDirty(version.dirty())
			.datasetSemanticVersion(version.semanticVersion())
			.knowledgeManifest(knowledgeManifest)
			.timestamp(Instant.now())
			.items(results)
			.metadata(config.metadata())
			.aggregateScores(aggregateScores)
			.passRate(passRate)
			.totalCostUsd(totalCost)
			.totalTokens(totalTokens)
			.totalDurationMs(totalDurationMs)
			.codeVersion(codeVersion)
			.codeDirty(codeDirty)
			.conditions(runConditions(agentInvoker, config))
			.build();

		resultStore.save(experimentResult);

		if (activeSession != null && sessionStore != null) {
			sessionStore.saveVariantToSession(activeSession.sessionName(), activeSession.experimentName(),
					activeSession.variantName(), experimentResult);
		}

		logger.info("Experiment '{}' complete: passRate={}, cost=${}, tokens={}, duration={}ms",
				config.experimentName(), String.format("%.1f%%", passRate * 100), String.format("%.4f", totalCost),
				totalTokens, totalDurationMs);

		return experimentResult;
	}

	/**
	 * Opens the experiment's run journal. On by default; a durable journal needs a run
	 * directory (it is written beside the experiment artifacts). When journaling is
	 * requested but there is no output directory, announce the degradation once
	 * (fail-loud spirit) and fall back to a no-op.
	 */
	private ExperimentJournal openExperimentJournal(@Nullable Path runDir) {
		if (!config.shouldJournal()) {
			return ExperimentJournal.disabled(config.experimentName());
		}
		if (runDir == null) {
			logger.warn("Journaling is enabled but no outputDir is configured — the canonical per-step "
					+ "cost journal (analysis.jsonl) will not be written. Set outputDir, or call "
					+ "withoutJournal() to silence this.");
			return ExperimentJournal.disabled(config.experimentName());
		}
		return ExperimentJournal.open(ExperimentJournal.journalRoot(runDir), config.experimentName());
	}

	/**
	 * Records every {@link PhaseCapture} the invoker returned into a per-item journal
	 * run. The variant/arm and session labels are carried into the journal's
	 * {@code run.json} (so per-arm measurement is recoverable from the journal alone); a
	 * non-session run uses the {@code "default"} variant sentinel. Journal failures are
	 * logged but never demote an otherwise-good item result — except the slice-1
	 * fail-loud {@code IllegalStateException} (derived events on non-durable storage),
	 * which is a configuration bug the caller must see, so it propagates.
	 */
	private void journalItem(ExperimentJournal experimentJournal, DatasetItem item, InvocationResult invocationResult,
			@Nullable ActiveSession activeSession) {
		if (!experimentJournal.enabled()) {
			return;
		}
		String variant = activeSession != null ? activeSession.variantName() : "default";
		String session = activeSession != null ? activeSession.sessionName() : null;
		try (RunJournal journal = experimentJournal.openItem(item.id(), item.slug(), config.model(), variant, session,
				invocationResult.sessionId())) {
			for (PhaseCapture phase : invocationResult.phases()) {
				journal.recordPhase(phase);
			}
			journal.finish();
		}
		catch (IllegalStateException ex) {
			throw ex;
		}
		catch (RuntimeException ex) {
			logger.error("Failed to journal run for item {}: {}", item.id(), ex.getMessage(), ex);
		}
	}

	ItemResult runItem(AgentInvoker agentInvoker, DatasetItem item, String experimentId, @Nullable Path runDir,
			@Nullable ActiveSession activeSession, ExperimentJournal experimentJournal) {
		long startTime = System.currentTimeMillis();
		Path workspace = null;
		try {
			ResolvedItem resolved = datasetManager.resolve(item);
			workspace = workspaceProvisioner.provision(resolved);

			String prompt = buildPrompt(item);
			InvocationContext context = InvocationContext.builder()
				.workspacePath(workspace)
				.prompt(prompt)
				.systemPrompt(config.systemPrompt())
				.model(config.model())
				.timeout(config.perItemTimeout())
				.metadata(Map.of("experimentId", experimentId, "itemId", item.id(), "itemSlug", item.slug()))
				.runDir(runDir)
				.build();

			InvocationResult invocationResult = invokeWithTimeout(agentInvoker, context);
			long durationMs = System.currentTimeMillis() - startTime;

			// Own the run-journal lifecycle here — the framework, not the invoker (DESIGN
			// §4). The
			// invoker stays dumb (it only returned PhaseCaptures); we open a Run, record
			// each phase
			// (auto-emitting per-step StepCostEvents to analysis.jsonl), and finish.
			journalItem(experimentJournal, item, invocationResult, activeSession);

			if (!invocationResult.success()) {
				@Nullable Path preservedPath = preserveWorkspace(workspace, experimentId, item.slug(), activeSession);
				return ItemResult.builder()
					.itemId(item.id())
					.itemSlug(item.slug())
					.success(false)
					.passed(false)
					.costUsd(invocationResult.totalCostUsd())
					.totalTokens(invocationResult.totalTokens())
					.durationMs(durationMs)
					.scores(Map.of())
					.metrics(buildMetrics(invocationResult))
					.executionDetail(invocationResult)
					.workspacePath(preservedPath)
					.metadata(Map.of())
					.build();
			}

			// Judge the result
			@Nullable Path referenceDir = resolved.referenceDir();
			JudgmentContext judgmentContext = JudgmentContextFactory.create(item, workspace, invocationResult,
					referenceDir, resolved.beforeDir(), null, config);
			Verdict verdict = jury.vote(judgmentContext);

			Map<String, Double> scores = new LinkedHashMap<>(VerdictExtractor.extractScores(verdict));
			boolean passed = VerdictExtractor.passed(verdict);

			@Nullable Path preservedPath = preserveWorkspace(workspace, experimentId, item.slug(), activeSession);
			return ItemResult.builder()
				.itemId(item.id())
				.itemSlug(item.slug())
				.success(true)
				.passed(passed)
				.costUsd(invocationResult.totalCostUsd())
				.totalTokens(invocationResult.totalTokens())
				.durationMs(durationMs)
				.scores(scores)
				.metrics(buildMetrics(invocationResult))
				.executionDetail(invocationResult)
				.verdict(RecordedVerdict.from(verdict))
				.workspacePath(preservedPath)
				.metadata(Map.of())
				.build();

		}
		catch (Exception ex) {
			long durationMs = System.currentTimeMillis() - startTime;
			logger.error("Item {} failed: {}", item.id(), ex.getMessage(), ex);
			@Nullable Path preservedPath = preserveWorkspace(workspace, experimentId, item.slug(), activeSession);
			return ItemResult.builder()
				.itemId(item.id())
				.itemSlug(item.slug())
				.success(false)
				.passed(false)
				.durationMs(durationMs)
				.scores(Map.of())
				.metrics(Map.of())
				.workspacePath(preservedPath)
				.metadata(Map.of("error", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getName()))
				.build();
		}
		finally {
			if (workspace != null) {
				workspaceProvisioner.release(workspace);
			}
		}
	}

	String buildPrompt(DatasetItem item) {
		String prompt = config.promptTemplate().replace("{{task}}", item.developerTask());
		if (!item.knowledgeRefs().isEmpty()) {
			prompt = prompt.replace("{{knowledgeRefs}}", String.join("\n", item.knowledgeRefs()));
		}
		else {
			prompt = prompt.replace("{{knowledgeRefs}}", "");
		}
		return prompt;
	}

	private InvocationResult invokeWithTimeout(AgentInvoker agentInvoker, InvocationContext context)
			throws AgentInvocationException {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Callable<InvocationResult> task = () -> agentInvoker.invoke(context);
			Future<InvocationResult> future = executor.submit(task);
			return future.get(context.timeout().toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException ex) {
			logger.warn("Item invocation timed out after {}", context.timeout());
			return InvocationResult.timeout(context.timeout().toMillis(), context.metadata(),
					"Timed out after " + context.timeout());
		}
		catch (ExecutionException ex) {
			if (ex.getCause() instanceof AgentInvocationException aie) {
				throw aie;
			}
			throw new AgentInvocationException("Agent invocation failed", ex.getCause());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AgentInvocationException("Agent invocation interrupted", ex);
		}
		finally {
			executor.shutdownNow();
		}
	}

	/**
	 * Preserve the workspace by copying it to the artifact directory. Returns the
	 * destination path, or null if preservation is disabled or the workspace is null.
	 *
	 * <p>
	 * Copies rather than moves so the original workspace survives for the
	 * {@link WorkspaceProvisioner} to release — releasing may delete an ephemeral
	 * workspace or retain a reused git checkout.
	 */
	private @Nullable Path preserveWorkspace(@Nullable Path workspace, String experimentId, String itemSlug,
			@Nullable ActiveSession activeSession) {
		if (workspace == null || !config.shouldPreserveWorkspaces()) {
			return null;
		}
		Path destination;
		if (activeSession != null) {
			destination = config.outputDir()
				.resolve(config.experimentName())
				.resolve("sessions")
				.resolve(activeSession.sessionName())
				.resolve("workspaces")
				.resolve(activeSession.variantName())
				.resolve(itemSlug);
		}
		else {
			destination = config.outputDir()
				.resolve(config.experimentName())
				.resolve(experimentId)
				.resolve("workspaces")
				.resolve(itemSlug);
		}
		try {
			Files.createDirectories(destination);
			copyDirectory(workspace, destination);
			logger.info("Preserved workspace for {} at {}", itemSlug, destination);
			return destination;
		}
		catch (IOException ex) {
			logger.warn("Failed to preserve workspace for {}: {}", itemSlug, ex.getMessage());
			return null;
		}
	}

	private static void copyDirectory(Path source, Path target) throws IOException {
		Files.walkFileTree(source, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				Files.createDirectories(target.resolve(source.relativize(dir)));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	private static Map<String, Object> buildMetrics(InvocationResult result) {
		ToolTelemetry telemetry = result.toolTelemetry();
		return Map.of("input_tokens", result.inputTokens(), "output_tokens", result.outputTokens(), "thinking_tokens",
				result.thinkingTokens(), "num_turns", telemetry.numTurns(), "tool_calls", telemetry.totalToolCalls(),
				"permission_denials", telemetry.permissionDenials());
	}

	private static Map<String, Double> aggregateScores(List<ItemResult> results) {
		if (results.isEmpty()) {
			return Map.of();
		}
		// Collect all judge names
		java.util.Set<String> judgeNames = new java.util.LinkedHashSet<>();
		for (ItemResult result : results) {
			judgeNames.addAll(result.scores().keySet());
		}
		// Compute mean per judge (only items that have a score for that judge)
		java.util.Map<String, Double> aggregates = new java.util.LinkedHashMap<>();
		for (String judge : judgeNames) {
			double sum = 0;
			int count = 0;
			for (ItemResult result : results) {
				Double score = result.scores().get(judge);
				if (score != null) {
					sum += score;
					count++;
				}
			}
			if (count > 0) {
				aggregates.put(judge, sum / count);
			}
		}
		return Map.copyOf(aggregates);
	}

}
