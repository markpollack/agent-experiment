package io.github.markpollack.experiment.diagnostic;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Diagnostic analysis for a single dataset item.
 *
 * @param itemId stable fixture ID (matches across runs)
 * @param checks classified diagnostic checks for this item
 * @param dominantGap the most frequent gap category for this item (null if no gaps or
 * unclassifiable)
 */
public record ItemDiagnostic(String itemId, List<DiagnosticCheck> checks, @Nullable GapCategory dominantGap) {

	public ItemDiagnostic {
		checks = List.copyOf(checks);
	}

}
