package io.github.markpollack.experiment.diagnostic;

import java.util.List;
import java.util.Objects;

/**
 * The output of the diagnostic reasoning pipeline: deterministic remediations,
 * LLM-proposed artifacts, and checks that neither layer could resolve.
 *
 * @param experimentId the experiment run that was analyzed
 * @param remediations highest-impact-first remediation actions (deterministic + LLM)
 * @param proposals LLM-generated proposals for new deterministic artifacts (the flywheel)
 * @param unresolvedChecks diagnostic checks that neither layer could handle
 */
public record RemediationReport(String experimentId, List<RemediationAction> remediations,
		List<RemediationProposal> proposals, List<DiagnosticCheck> unresolvedChecks) {

	public RemediationReport {
		Objects.requireNonNull(experimentId, "experimentId");
		remediations = List.copyOf(remediations);
		proposals = List.copyOf(proposals);
		unresolvedChecks = List.copyOf(unresolvedChecks);
	}

}
