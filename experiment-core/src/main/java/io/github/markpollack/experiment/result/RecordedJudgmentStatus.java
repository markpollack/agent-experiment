package io.github.markpollack.experiment.result;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.github.markpollack.judge.result.JudgmentStatus;

/** Stable Agent Experiment wire status for a recorded judgment. */
public enum RecordedJudgmentStatus {

	PASS("pass"),

	FAIL("fail"),

	ABSTAIN("abstain"),

	/**
	 * The criterion does not apply to this subject, so nothing about it was assessed.
	 *
	 * <p>
	 * Distinct from {@link #ABSTAIN}, which means the criterion applies and the judge
	 * could not decide. An abstention counts against the subject; a not-applicable leaves
	 * the denominator entirely and is counted on its own.
	 */
	NOT_APPLICABLE("not_applicable"),

	ERROR("error");

	private final String wireName;

	RecordedJudgmentStatus(String wireName) {
		this.wireName = wireName;
	}

	@JsonValue
	public String wireName() {
		return this.wireName;
	}

	@JsonCreator
	public static RecordedJudgmentStatus fromWire(String value) {
		for (RecordedJudgmentStatus status : values()) {
			if (status.wireName.equalsIgnoreCase(value) || status.name().equalsIgnoreCase(value)) {
				return status;
			}
		}
		throw new IllegalArgumentException("Unknown recorded judgment status: " + value);
	}

	static RecordedJudgmentStatus from(JudgmentStatus status) {
		return valueOf(status.name());
	}

}
