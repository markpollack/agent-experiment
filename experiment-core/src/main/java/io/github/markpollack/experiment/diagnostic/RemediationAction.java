package io.github.markpollack.experiment.diagnostic;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * A concrete remediation recommendation produced by the diagnostic reasoner.
 *
 * @param target the component to fix (e.g. "pom-upgrader", "PomAnalyzer",
 * "planner-prompt")
 * @param actionType what kind of fix
 * @param summary one-line description of the fix
 * @param detail multi-line evidence and reasoning
 * @param confidence how certain this recommendation is
 * @param sourceCheck the diagnostic check that triggered this recommendation (null if
 * synthetic)
 */
public record RemediationAction(String target, ActionType actionType, String summary, String detail,
		Confidence confidence, @Nullable DiagnosticCheck sourceCheck) {

	public RemediationAction {
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(actionType, "actionType");
		Objects.requireNonNull(summary, "summary");
		Objects.requireNonNull(detail, "detail");
		Objects.requireNonNull(confidence, "confidence");
	}

}
