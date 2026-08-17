package io.github.markpollack.experiment.result;

import io.github.markpollack.judge.result.Check;

/** Agent Experiment-owned persisted form of a judge check. */
public record RecordedCheck(String name, boolean passed, String message) {

	public RecordedCheck {
		java.util.Objects.requireNonNull(name, "name must not be null");
		java.util.Objects.requireNonNull(message, "message must not be null");
	}

	public static RecordedCheck from(Check check) {
		return new RecordedCheck(check.name(), check.passed(), check.message());
	}

	public static RecordedCheck pass(String name, String message) {
		return new RecordedCheck(name, true, message);
	}

	public static RecordedCheck fail(String name, String message) {
		return new RecordedCheck(name, false, message);
	}

}
