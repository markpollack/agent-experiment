package io.github.markpollack.experiment.diagnostic;

import org.jspecify.annotations.Nullable;
import io.github.markpollack.experiment.result.RecordedCheck;

/**
 * A recorded judge {@link RecordedCheck} enriched with gap classification.
 *
 * @param judgeName the name of the judge that produced the check
 * @param check the original judge check
 * @param gapCategory the classified gap category (null if unclassifiable)
 * @param rationale why this gap category was assigned
 */
public record DiagnosticCheck(String judgeName, RecordedCheck check, @Nullable GapCategory gapCategory,
		@Nullable String rationale) {

	/** Create a diagnostic from a live Agent Judge check. */
	public DiagnosticCheck(String judgeName, io.github.markpollack.judge.result.Check check,
			@Nullable GapCategory gapCategory, @Nullable String rationale) {
		this(judgeName, RecordedCheck.from(check), gapCategory, rationale);
	}

}
