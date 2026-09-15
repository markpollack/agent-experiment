package io.github.markpollack.experiment.result.history;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.markpollack.experiment.result.RecordedJudgment;
import io.github.markpollack.experiment.result.RecordedJudgmentStatus;
import org.jspecify.annotations.Nullable;

/**
 * A judgment as it was stored, including the facts it did not store.
 *
 * <p>
 * Live {@link RecordedJudgment} is strict: every fact the contract demands is there. This
 * type is what a stored file decodes into, so a fact that was never recorded stays
 * <em>unrecorded</em> rather than being defaulted into a value that reads as a
 * measurement.
 *
 * <p>
 * Absent is not the same as unrecorded. A reason code is required only on an ERROR, so
 * its absence on a PASS is simply a fact that does not apply; its absence on an ERROR is
 * a required fact missing, and that makes the judgment unconvertible.
 *
 * @param status the outcome, or null when the file recorded none
 * @param score normalized score, or null
 * @param label classification label, or null
 * @param reasonCode why the status was reached, or null
 * @param reasoning human-readable explanation
 * @param checks recorded check evidence, each with its own absences
 * @param metadata portable judgment metadata
 */
public record HistoricalJudgment(@Nullable RecordedJudgmentStatus status, @Nullable Double score,
		@Nullable String label, @Nullable String reasonCode, String reasoning, List<HistoricalCheck> checks,
		Map<String, Object> metadata) {

	public HistoricalJudgment {
		checks = List.copyOf(checks);
		metadata = Map.copyOf(metadata);
	}

	/** Required facts this judgment did not record, as paths. Empty means convertible. */
	public List<String> unrecorded(String path) {
		List<String> missing = new ArrayList<>();
		if (this.status == null) {
			missing.add(path + ".status");
		}
		// A reason code is required only on an ERROR. Its absence anywhere else is a fact
		// that does not apply, not a fact that went missing.
		if (this.status == RecordedJudgmentStatus.ERROR && (this.reasonCode == null || this.reasonCode.isBlank())) {
			missing.add(path + ".reasonCode");
		}
		for (int i = 0; i < this.checks.size(); i++) {
			missing.addAll(this.checks.get(i).unrecorded(path + ".checks[" + i + "]"));
		}
		return missing;
	}

	/**
	 * The live judgment this stored one represents.
	 * @throws IllegalStateException when a required fact was never recorded; no fact is
	 * invented to make the conversion succeed
	 */
	public RecordedJudgment toLive() {
		List<String> missing = unrecorded("judgment");
		if (!missing.isEmpty()) {
			throw new IllegalStateException("cannot convert a judgment with unrecorded required facts: " + missing);
		}
		return new RecordedJudgment(this.status, this.score, this.label, this.reasonCode, this.reasoning,
				this.checks.stream().map(HistoricalCheck::toLive).toList(), this.metadata);
	}

}
