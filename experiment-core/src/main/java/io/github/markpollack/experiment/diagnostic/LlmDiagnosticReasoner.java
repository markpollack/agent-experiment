package io.github.markpollack.experiment.diagnostic;

import java.util.List;

/**
 * LLM-powered diagnostic reasoning for checks that the deterministic layer could not
 * resolve.
 *
 * <p>
 * Serves two purposes:
 * <ol>
 * <li><b>Trajectory analysis</b>: analyzes agent execution trajectory (thinking blocks,
 * tool calls, tool results) via {@link ReasoningContext} to understand why failures
 * occurred.</li>
 * <li><b>Flywheel proposals</b>: proposes new deterministic artifacts (rules, KB entries,
 * tool enhancements, prompt patches) that, once human-reviewed and applied, convert
 * LLM-discovered patterns into deterministic infrastructure.</li>
 * </ol>
 *
 * <p>
 * Implementation lives in experiment-claude (requires Claude SDK client).
 */
public interface LlmDiagnosticReasoner {

	/**
	 * Analyze unresolved checks using trajectory data and propose fixes.
	 * @param unresolvedChecks checks that the deterministic layer could not handle
	 * @param context the full reasoning context including trajectory and file pointers
	 * @return immediate actions (LLM_INFERRED confidence) and proposals for new
	 * deterministic artifacts
	 */
	LlmReasoningResult reasonUnresolved(List<DiagnosticCheck> unresolvedChecks, ReasoningContext context);

}
