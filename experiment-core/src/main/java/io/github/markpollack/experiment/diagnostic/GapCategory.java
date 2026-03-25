package io.github.markpollack.experiment.diagnostic;

/**
 * Structured gap categories identifying where in the pipeline a failure originated.
 *
 * <p>
 * Gap assignment is post-hoc — applied by a {@link GapClassifier} after the jury produces
 * verdicts, not embedded in the judges themselves.
 */
public enum GapCategory {

	AGENT_EXECUTION_GAP("Plan was correct, agent didn't follow it",
			"Improve agent prompting, reflexion, or tool usage"),

	PLAN_GENERATION_GAP("Plan didn't cover this migration pattern", "Improve planner prompt or planning model"),

	KB_GAP("Knowledge base doesn't cover this pattern", "Add knowledge store entry"),

	TOOL_GAP("No deterministic tool handles this transformation", "Build new tool (like thymeleaf-migrator)"),

	ANALYSIS_GAP("Static analysis missed a relevant signal or data is missing",
			"Improve analysis tools or workspace preservation"),

	CRITERIA_GAP("VERIFY criteria were redundant, ambiguous, or missing", "Improve planner's criteria generation"),

	EVALUATION_GAP("The jury itself is wrong (false positive/negative)",
			"Calibrate judge against gold set, measure TPR/TNR"),

	STOCHASTICITY_GAP("Same config produces different outcomes across runs",
			"Not fixable per se; requires N>=3 runs to separate signal from noise");

	private final String description;

	private final String actionableAdvice;

	GapCategory(String description, String actionableAdvice) {
		this.description = description;
		this.actionableAdvice = actionableAdvice;
	}

	public String description() {
		return description;
	}

	public String actionableAdvice() {
		return actionableAdvice;
	}

}
