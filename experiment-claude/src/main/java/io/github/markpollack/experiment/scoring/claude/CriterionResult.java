package io.github.markpollack.experiment.scoring.claude;

/**
 * Result of evaluating a single criterion against a workspace.
 *
 * @param criterion the criterion text that was evaluated
 * @param passed whether the criterion was satisfied
 * @param reasoning LLM-generated explanation of the evaluation
 * @param confidence confidence in the evaluation (0.0–1.0)
 */
record CriterionResult(String criterion, boolean passed, String reasoning, double confidence) {
}
