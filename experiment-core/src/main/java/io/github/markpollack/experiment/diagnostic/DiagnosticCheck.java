package io.github.markpollack.experiment.diagnostic;

import org.jspecify.annotations.Nullable;
import org.springaicommunity.judge.result.Check;

/**
 * A judge {@link Check} enriched with gap classification.
 *
 * @param judgeName the name of the judge that produced the check
 * @param check the original judge check
 * @param gapCategory the classified gap category (null if unclassifiable)
 * @param rationale why this gap category was assigned
 */
public record DiagnosticCheck(String judgeName, Check check, @Nullable GapCategory gapCategory,
		@Nullable String rationale) {

}
