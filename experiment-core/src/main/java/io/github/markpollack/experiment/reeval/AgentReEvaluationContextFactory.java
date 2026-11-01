package io.github.markpollack.experiment.reeval;

import java.time.Duration;
import java.util.Optional;

import io.github.markpollack.experiment.agent.InvocationResult;
import io.github.markpollack.experiment.agent.TerminalStatus;
import io.github.markpollack.experiment.result.ItemResult;
import io.github.markpollack.experiment.scoring.AgentJudgingInput;

/**
 * Default context reconstruction for agent experiment results.
 *
 * <p>
 * Reconstructs a {@link AgentJudgingInput} from a stored {@link ItemResult} whose
 * {@code executionDetail} is an {@link InvocationResult}. Returns
 * {@code Optional.empty()} for failed items or items without an {@code InvocationResult}
 * execution detail.
 */
public class AgentReEvaluationContextFactory implements ReEvaluationContextFactory {

	public static AgentReEvaluationContextFactory defaults() {
		return new AgentReEvaluationContextFactory();
	}

	@Override
	public Optional<AgentJudgingInput> create(ItemResult item) {
		if (!item.success()) {
			return Optional.empty();
		}
		if (!(item.executionDetail() instanceof InvocationResult invocationResult)) {
			return Optional.empty();
		}

		AgentJudgingInput.Builder builder = AgentJudgingInput.builder()
			.goal(item.itemSlug())
			.executionTime(Duration.ofMillis(invocationResult.durationMs()))
			.status(mapStatus(invocationResult.status()));

		if (item.workspacePath() != null) {
			builder.workspace(item.workspacePath());
		}

		// Forward stored invocation metadata into context
		if (invocationResult.metadata() != null) {
			for (var entry : invocationResult.metadata().entrySet()) {
				builder.metadata(entry.getKey(), entry.getValue());
			}
		}

		return Optional.of(builder.build());
	}

	private static TerminalStatus mapStatus(TerminalStatus status) {
		return switch (status) {
			case COMPLETED -> TerminalStatus.COMPLETED;
			case TIMEOUT -> TerminalStatus.TIMEOUT;
			// A ceiling cut the agent off. Closer to a timeout than a failure: the agent
			// did not
			// fail at the task, it was not permitted to finish it.
			case MAX_TURNS -> TerminalStatus.TIMEOUT;
			case ERROR -> TerminalStatus.ERROR;
		};
	}

}
