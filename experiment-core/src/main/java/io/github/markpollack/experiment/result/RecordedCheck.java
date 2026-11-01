package io.github.markpollack.experiment.result;

import io.github.markpollack.judge.judgment.Check;
import org.jspecify.annotations.Nullable;

/** A complete child judgment with an explicitly historical Boolean compatibility view. */
public record RecordedCheck(String name, @Nullable Boolean passed, String message,
		@Nullable RecordedJudgment judgment) {
	public RecordedCheck {
		java.util.Objects.requireNonNull(name);
		java.util.Objects.requireNonNull(message);
	}

	public RecordedCheck(String name, boolean passed, String message) {
		this(name, passed, message, null);
	}

	public static RecordedCheck from(Check check) {
		Boolean value = switch (check.judgment().status()) {
			case PASS -> true;
			case FAIL -> false;
			default -> null;
		};
		return new RecordedCheck(check.id(), value, check.judgment().reasoning(),
				RecordedJudgment.from(check.judgment()));
	}

	public static RecordedCheck pass(String name, String message) {
		return new RecordedCheck(name, true, message);
	}

	public static RecordedCheck fail(String name, String message) {
		return new RecordedCheck(name, false, message);
	}
}
