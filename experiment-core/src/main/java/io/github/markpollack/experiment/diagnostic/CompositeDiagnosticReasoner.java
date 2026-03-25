package io.github.markpollack.experiment.diagnostic;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chains deterministic reasoning with optional LLM fallback. The deterministic layer runs
 * first; unresolved checks are forwarded to the LLM layer (if present). The LLM layer
 * produces both immediate actions and proposals for new deterministic artifacts (the
 * flywheel).
 */
public class CompositeDiagnosticReasoner implements DiagnosticReasoner {

	private static final Logger logger = LoggerFactory.getLogger(CompositeDiagnosticReasoner.class);

	private final DeterministicReasoner deterministic;

	private final @Nullable LlmDiagnosticReasoner llmFallback;

	public CompositeDiagnosticReasoner(DeterministicReasoner deterministic,
			@Nullable LlmDiagnosticReasoner llmFallback) {
		this.deterministic = deterministic;
		this.llmFallback = llmFallback;
	}

	public CompositeDiagnosticReasoner(DeterministicReasoner deterministic) {
		this(deterministic, null);
	}

	@Override
	public RemediationReport reason(DiagnosticReport report, ReasoningContext context) {
		RemediationReport deterministicReport = deterministic.reason(report, context);

		if (llmFallback == null || deterministicReport.unresolvedChecks().isEmpty()) {
			logger.debug("Deterministic-only: {} remediations, {} unresolved",
					deterministicReport.remediations().size(), deterministicReport.unresolvedChecks().size());
			return deterministicReport;
		}

		LlmReasoningResult llmResult = llmFallback.reasonUnresolved(deterministicReport.unresolvedChecks(), context);

		List<RemediationAction> merged = new ArrayList<>(deterministicReport.remediations());
		merged.addAll(llmResult.actions());

		// Unresolved = whatever the LLM couldn't handle either
		List<DiagnosticCheck> stillUnresolved = new ArrayList<>(deterministicReport.unresolvedChecks());
		for (RemediationAction llmAction : llmResult.actions()) {
			if (llmAction.sourceCheck() != null) {
				stillUnresolved.remove(llmAction.sourceCheck());
			}
		}

		logger.debug("Composite: {} deterministic + {} LLM remediations, {} proposals, {} still unresolved",
				deterministicReport.remediations().size(), llmResult.actions().size(), llmResult.proposals().size(),
				stillUnresolved.size());

		return new RemediationReport(report.experimentId(), merged, llmResult.proposals(), stillUnresolved);
	}

}
