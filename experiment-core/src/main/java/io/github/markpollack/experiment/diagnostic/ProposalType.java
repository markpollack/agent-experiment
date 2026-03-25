package io.github.markpollack.experiment.diagnostic;

/**
 * What kind of deterministic artifact the LLM proposes to create. Each proposal, once
 * human-reviewed and applied, converts an LLM-discovered pattern into deterministic
 * infrastructure — the flywheel.
 */
public enum ProposalType {

	NEW_REASONER_RULE("New rule for DeterministicReasoner"),

	KB_ENTRY_DRAFT("New knowledge base entry"),

	TOOL_ENHANCEMENT("Enhancement to an existing deterministic tool"),

	PROMPT_PATCH("Patch to a planner or agent prompt"),

	NEW_TOOL_SPEC("Specification for a new deterministic tool"),

	JUDGE_CALIBRATION("Calibration change for a judge");

	private final String description;

	ProposalType(String description) {
		this.description = description;
	}

	public String description() {
		return description;
	}

}
