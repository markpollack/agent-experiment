package io.github.markpollack.experiment.scoring;

import java.nio.file.Path;
import java.time.Duration;

import io.github.markpollack.experiment.agent.InvocationResult;
import io.github.markpollack.experiment.agent.TerminalStatus;
import io.github.markpollack.experiment.dataset.DatasetItem;
import io.github.markpollack.experiment.pipeline.AnalysisEnvelope;
import io.github.markpollack.experiment.pipeline.ExecutionPlan;
import io.github.markpollack.experiment.runner.ExperimentConfig;
import org.jspecify.annotations.Nullable;
import org.springaicommunity.judge.context.ExecutionStatus;
import org.springaicommunity.judge.context.JudgmentContext;

/**
 * Factory for creating {@link JudgmentContext} instances from experiment-driver types.
 *
 * <p>
 * Maps experiment-driver domain objects ({@link DatasetItem}, {@link InvocationResult})
 * to agent-judge-core's {@link JudgmentContext} for jury evaluation. The
 * {@code expectedDir} metadata key is set to the reference directory path, which
 * {@code FileComparisonJudge} uses for semantic file comparison.
 *
 * <p>
 * The enriched overload populates additional metadata for tiered jury evaluation:
 * {@code beforeDir}, {@code analysis}, {@code plan}, {@code targetBootVersion},
 * {@code targetJavaVersion}, and {@code targetClassVersion}. All enrichment parameters
 * are nullable — missing data is simply omitted from the context metadata.
 */
public final class JudgmentContextFactory {

	private JudgmentContextFactory() {
	}

	/**
	 * Create a {@link JudgmentContext} from experiment-driver types.
	 * @param item the dataset item being evaluated
	 * @param workspace the workspace directory where the agent operated
	 * @param invocationResult the result of the agent invocation
	 * @param referenceDir the reference directory for expected output comparison, or
	 * {@code null} if no physical reference directory is available
	 * @return a fully populated JudgmentContext
	 */
	public static JudgmentContext create(DatasetItem item, Path workspace, InvocationResult invocationResult,
			@Nullable Path referenceDir) {
		return create(item, workspace, invocationResult, referenceDir, null, null, null, null);
	}

	/**
	 * Create an enriched {@link JudgmentContext} with pipeline and configuration
	 * metadata.
	 *
	 * <p>
	 * Populates metadata keys used by tiered jury judges:
	 * <ul>
	 * <li>{@code expectedDir} — reference directory for FileComparisonJudge</li>
	 * <li>{@code beforeDir} — pre-execution workspace state for ASTDiffJudge</li>
	 * <li>{@code analysis} — {@link AnalysisEnvelope} for migration/LLM judges</li>
	 * <li>{@code plan} — {@link ExecutionPlan} for LLM judges</li>
	 * <li>{@code targetBootVersion} — target Spring Boot version for
	 * DependencyVersionJudge</li>
	 * <li>{@code targetJavaVersion} — target Java version for DependencyVersionJudge</li>
	 * <li>{@code targetClassVersion} — target class file version for
	 * ClassVersionJudge</li>
	 * </ul>
	 * @param item the dataset item being evaluated
	 * @param workspace the workspace directory where the agent operated
	 * @param invocationResult the result of the agent invocation
	 * @param referenceDir the reference directory (nullable)
	 * @param beforeDir the pre-execution workspace directory for baseline comparison
	 * (nullable)
	 * @param analysis the pipeline analysis envelope (nullable)
	 * @param plan the pipeline execution plan (nullable)
	 * @param config the experiment configuration (nullable)
	 * @return a fully populated JudgmentContext with enrichment metadata
	 */
	public static JudgmentContext create(DatasetItem item, Path workspace, InvocationResult invocationResult,
			@Nullable Path referenceDir, @Nullable Path beforeDir, @Nullable AnalysisEnvelope analysis,
			@Nullable ExecutionPlan plan, @Nullable ExperimentConfig config) {
		JudgmentContext.Builder builder = JudgmentContext.builder()
			.goal(item.developerTask())
			.workspace(workspace)
			.executionTime(Duration.ofMillis(invocationResult.durationMs()))
			.status(mapStatus(invocationResult.status()));

		if (referenceDir != null) {
			builder.metadata("expectedDir", referenceDir);
		}
		if (beforeDir != null) {
			builder.metadata("beforeDir", beforeDir);
		}
		if (analysis != null) {
			builder.metadata("analysis", analysis);
		}
		if (plan != null) {
			builder.metadata("plan", plan);
		}
		if (config != null) {
			enrichFromConfig(builder, config);
		}

		// Forward invoker metadata (e.g. coverage metrics) into JudgmentContext
		if (invocationResult.metadata() != null) {
			for (var entry : invocationResult.metadata().entrySet()) {
				builder.metadata(entry.getKey(), entry.getValue());
			}
		}

		return builder.build();
	}

	/**
	 * Map experiment-driver {@link TerminalStatus} to agent-judge-core
	 * {@link ExecutionStatus}.
	 * @param status the terminal status from the agent invocation
	 * @return the corresponding execution status
	 */
	static ExecutionStatus mapStatus(TerminalStatus status) {
		return switch (status) {
			case COMPLETED -> ExecutionStatus.SUCCESS;
			case TIMEOUT -> ExecutionStatus.TIMEOUT;
			case ERROR -> ExecutionStatus.FAILED;
		};
	}

	private static void enrichFromConfig(JudgmentContext.Builder builder, ExperimentConfig config) {
		String targetBootVersion = config.metadata().get("targetBootVersion");
		if (targetBootVersion != null) {
			builder.metadata("targetBootVersion", targetBootVersion);
		}
		String targetJavaVersion = config.metadata().get("targetJavaVersion");
		if (targetJavaVersion != null) {
			builder.metadata("targetJavaVersion", targetJavaVersion);
		}
		String targetClassVersion = config.metadata().get("targetClassVersion");
		if (targetClassVersion != null) {
			builder.metadata("targetClassVersion", Integer.parseInt(targetClassVersion));
		}
	}

}
