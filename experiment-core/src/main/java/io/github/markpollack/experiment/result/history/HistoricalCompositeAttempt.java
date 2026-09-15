package io.github.markpollack.experiment.result.history;

import java.util.ArrayList;
import java.util.List;

import io.github.markpollack.experiment.result.RecordedCompositeAttempt;
import org.jspecify.annotations.Nullable;

/**
 * A composite attempt as it was stored, including the facts it did not store.
 *
 * @param name tier or member name
 * @param relation how this attempt related to its parent
 * @param policy tier policy, when recorded
 * @param disposition whether the parent could use what the stage returned; null means the
 * file recorded none, which is a required fact missing
 * @param dispositionReason why a stage failed, when it did
 * @param verdict what the stage returned, when it returned one
 * @param failureCode why the stage produced no verdict, when it produced none
 */
public record HistoricalCompositeAttempt(String name, String relation, @Nullable String policy,
		@Nullable String disposition, @Nullable String dispositionReason, @Nullable HistoricalVerdict verdict,
		@Nullable String failureCode) {

	public HistoricalCompositeAttempt {
		java.util.Objects.requireNonNull(name, "name must not be null");
		java.util.Objects.requireNonNull(relation, "relation must not be null");
	}

	/** Required facts this attempt and its verdict did not record. */
	public List<String> unrecorded(String path) {
		List<String> missing = new ArrayList<>();
		if (this.disposition == null || this.disposition.isBlank()) {
			missing.add(path + ".disposition");
		}
		if (this.verdict != null) {
			missing.addAll(this.verdict.unrecorded(path + ".verdict"));
		}
		return missing;
	}

	/**
	 * The live attempt this stored one represents.
	 * @throws IllegalStateException when a required fact was never recorded
	 */
	public RecordedCompositeAttempt toLive() {
		List<String> missing = unrecorded("attempt");
		if (!missing.isEmpty()) {
			throw new IllegalStateException("cannot convert an attempt with unrecorded required facts: " + missing);
		}
		return new RecordedCompositeAttempt(this.name, this.relation, this.policy, this.disposition,
				this.dispositionReason, this.verdict != null ? this.verdict.toLive() : null, this.failureCode);
	}

}
