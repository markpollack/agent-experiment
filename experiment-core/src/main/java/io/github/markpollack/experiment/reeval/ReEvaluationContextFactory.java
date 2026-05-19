package io.github.markpollack.experiment.reeval;

import java.util.Optional;

import io.github.markpollack.experiment.result.ItemResult;
import io.github.markpollack.judge.context.JudgmentContext;

/**
 * Reconstructs a {@link JudgmentContext} from a stored {@link ItemResult} for
 * re-evaluation.
 *
 * <p>
 * Returns {@code Optional.empty()} if the item cannot be re-evaluated (failed item,
 * missing execution detail, workspace not preserved, etc.). Returns
 * {@code Optional.of(context)} when reconstruction succeeds.
 *
 * <p>
 * This is a {@code @FunctionalInterface}: lambda-friendly for custom reconstruction
 * logic. Default implementation: {@code AgentReEvaluationContextFactory} for agent
 * experiment results.
 */
@FunctionalInterface
public interface ReEvaluationContextFactory {

	/**
	 * Attempt to reconstruct a JudgmentContext from a stored ItemResult. Returns empty if
	 * re-evaluation is not possible for this item.
	 * @param item the stored item result
	 * @return reconstructed context, or empty if re-evaluation is not possible
	 */
	Optional<JudgmentContext> create(ItemResult item);

}
