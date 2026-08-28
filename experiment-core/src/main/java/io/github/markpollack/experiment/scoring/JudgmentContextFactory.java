package io.github.markpollack.experiment.scoring;

import java.nio.file.Path;
import java.time.Duration;

import io.github.markpollack.experiment.agent.InvocationResult;
import io.github.markpollack.experiment.agent.TerminalStatus;
import io.github.markpollack.experiment.dataset.DatasetItem;
import io.github.markpollack.experiment.runner.ExperimentConfig;
import org.jspecify.annotations.Nullable;
import io.github.markpollack.judge.context.ExecutionStatus;
import io.github.markpollack.judge.context.JudgmentContext;

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
 * {@code beforeDir}, {@code plan}, and any {@code target*} keys carried in the experiment
 * configuration metadata. All enrichment parameters are nullable — missing data is simply
 * omitted from the context metadata.
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
		return create(item, workspace, invocationResult, referenceDir, null, null, null);
	}

	/**
	 * Create an enriched {@link JudgmentContext} with additional judging metadata.
	 *
	 * <p>
	 * Populates metadata keys used by tiered jury judges:
	 * <ul>
	 * <li>{@code expectedDir} — reference directory for FileComparisonJudge</li>
	 * <li>{@code beforeDir} — pre-execution workspace state for ASTDiffJudge</li>
	 * <li>{@code plan} — roadmap markdown for plan-derived LLM judges</li>
	 * <li>{@code targetBootVersion}, {@code targetJavaVersion},
	 * {@code targetClassVersion} — forwarded from experiment configuration metadata when
	 * present</li>
	 * </ul>
	 * @param item the dataset item being evaluated
	 * @param workspace the workspace directory where the agent operated
	 * @param invocationResult the result of the agent invocation
	 * @param referenceDir the reference directory (nullable)
	 * @param beforeDir the pre-execution workspace directory for baseline comparison
	 * (nullable)
	 * @param planRoadmap roadmap markdown describing the intended work (nullable)
	 * @param config the experiment configuration (nullable)
	 * @return a fully populated JudgmentContext with enrichment metadata
	 */
	public static JudgmentContext create(DatasetItem item, Path workspace, InvocationResult invocationResult,
			@Nullable Path referenceDir, @Nullable Path beforeDir, @Nullable String planRoadmap,
			@Nullable ExperimentConfig config) {
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
		if (planRoadmap != null) {
			builder.metadata("plan", planRoadmap);
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
			// A ceiling cut the agent off. Closer to a timeout than a failure: the agent
			// did not
			// fail at the task, it was not permitted to finish it.
			case MAX_TURNS -> ExecutionStatus.TIMEOUT;
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
