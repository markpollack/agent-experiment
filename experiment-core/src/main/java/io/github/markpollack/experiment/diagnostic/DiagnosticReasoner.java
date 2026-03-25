package io.github.markpollack.experiment.diagnostic;

/**
 * Turns gap-classified diagnostic checks into actionable remediation recommendations.
 *
 * <p>
 * Composable — takes the output of {@link DiagnosticAnalyzer#analyze} and produces a
 * {@link RemediationReport} with concrete fix suggestions. Implementations may be
 * deterministic (rule-based), LLM-powered, or a composite chain of both.
 */
public interface DiagnosticReasoner {

	RemediationReport reason(DiagnosticReport report, ReasoningContext context);

}
