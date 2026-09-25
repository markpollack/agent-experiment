package io.github.markpollack.experiment.recording;

import java.util.ArrayList;
import java.util.List;

/** Portable bounded failure facts; never a serialized Throwable. */
public record FailureFact(String phase, String kind, String message, List<String> causes) {
	public FailureFact {
		java.util.Objects.requireNonNull(phase);
		java.util.Objects.requireNonNull(kind);
		java.util.Objects.requireNonNull(message);
		causes = List.copyOf(causes);
	}

	public static FailureFact of(String phase, Throwable failure) {
		List<String> causes = new ArrayList<>();
		Throwable cause = failure.getCause();
		for (int i = 0; i < 4 && cause != null; i++, cause = cause.getCause())
			causes.add(cause.getClass().getName() + ": " + bounded(cause.getMessage()));
		return new FailureFact(phase, failure.getClass().getName(), bounded(failure.getMessage()), causes);
	}

	public static FailureFact of(String phase, String kind, String message) {
		return new FailureFact(phase, kind, bounded(message), List.of());
	}

	private static String bounded(String value) {
		if (value == null)
			return "";
		return value.substring(0, Math.min(2048, value.length()));
	}
}
