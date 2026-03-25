package io.github.markpollack.experiment.diagnostic;

import java.util.List;

/**
 * Output from the LLM diagnostic reasoning layer: immediate fix suggestions and proposals
 * for new deterministic artifacts.
 *
 * @param actions immediate remediation suggestions (with {@link Confidence#LLM_INFERRED})
 * @param proposals new deterministic artifacts to create (the flywheel)
 */
public record LlmReasoningResult(List<RemediationAction> actions, List<RemediationProposal> proposals) {

	public LlmReasoningResult {
		actions = List.copyOf(actions);
		proposals = List.copyOf(proposals);
	}

}
