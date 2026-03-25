package io.github.markpollack.experiment.diagnostic;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * An LLM-generated proposal for a new deterministic artifact. Each proposal, once
 * human-reviewed and applied, converts an LLM-discovered pattern into deterministic
 * infrastructure — the flywheel.
 *
 * @param actionType what kind of artifact to create
 * @param target which component to enhance (e.g. "DeterministicReasoner",
 * "dependency-changes.md")
 * @param proposalMarkdown full specification or content draft
 * @param proposalType what kind of deterministic artifact to create
 * @param confidence always {@link Confidence#LLM_INFERRED}
 * @param sourceCheck the diagnostic check that triggered this proposal (null if
 * synthetic)
 */
public record RemediationProposal(ActionType actionType, String target, String proposalMarkdown,
		ProposalType proposalType, Confidence confidence, @Nullable DiagnosticCheck sourceCheck) {

	public RemediationProposal {
		Objects.requireNonNull(actionType, "actionType");
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(proposalMarkdown, "proposalMarkdown");
		Objects.requireNonNull(proposalType, "proposalType");
		Objects.requireNonNull(confidence, "confidence");
	}

}
