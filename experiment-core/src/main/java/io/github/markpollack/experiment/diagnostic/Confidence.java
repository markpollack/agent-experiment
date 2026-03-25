package io.github.markpollack.experiment.diagnostic;

/**
 * How certain a remediation recommendation is.
 */
public enum Confidence {

	DETERMINISTIC("Structured data proves this is the right fix"),

	HEURISTIC("Partial match — probable but not certain"),

	LLM_INFERRED("Produced by LLM fallback analysis");

	private final String description;

	Confidence(String description) {
		this.description = description;
	}

	public String description() {
		return description;
	}

}
