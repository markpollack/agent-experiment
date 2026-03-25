package io.github.markpollack.experiment.diagnostic;

/**
 * What kind of remediation fix to apply.
 */
public enum ActionType {

	ADD_RULE("Add a new rule to an existing deterministic tool"),

	ENHANCE_TOOL("Enhance an existing tool's capabilities"),

	IMPROVE_PROMPT("Improve a planner or agent prompt"),

	ADD_KB_ENTRY("Add an entry to the knowledge base"),

	ENHANCE_ANALYSIS("Enhance static analysis to detect more signals"),

	CALIBRATE_JUDGE("Calibrate a judge's scoring or thresholds"),

	MANUAL_INVESTIGATION("Requires manual investigation — no automated fix identified");

	private final String description;

	ActionType(String description) {
		this.description = description;
	}

	public String description() {
		return description;
	}

}
