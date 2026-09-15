package io.github.markpollack.experiment.result.history;

import java.util.List;

import io.github.markpollack.experiment.result.RecordedCheck;
import org.jspecify.annotations.Nullable;

/**
 * A check as it was stored, including whether it recorded its outcome.
 *
 * <p>
 * A check with no recorded outcome is not a failed check. Defaulting it to false would
 * turn a gap in the record into evidence against the subject.
 *
 * @param name what was checked
 * @param passed the outcome, or null when the file recorded none
 * @param message the check's message
 */
public record HistoricalCheck(String name, @Nullable Boolean passed, String message) {

	public HistoricalCheck {
		java.util.Objects.requireNonNull(name, "name must not be null");
	}

	/** Required facts this check did not record. */
	public List<String> unrecorded(String path) {
		return this.passed == null ? List.of(path + ".passed") : List.of();
	}

	RecordedCheck toLive() {
		List<String> missing = unrecorded("check");
		if (!missing.isEmpty()) {
			throw new IllegalStateException("cannot convert a check with unrecorded required facts: " + missing);
		}
		return new RecordedCheck(this.name, this.passed, this.message);
	}

}
