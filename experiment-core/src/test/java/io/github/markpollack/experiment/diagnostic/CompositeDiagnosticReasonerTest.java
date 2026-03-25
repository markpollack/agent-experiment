package io.github.markpollack.experiment.diagnostic;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springaicommunity.judge.result.Check;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeDiagnosticReasonerTest {

	private final DeterministicReasoner deterministic = new DeterministicReasoner();

	@Test
	void deterministicOnly_passesThrough() {
		CompositeDiagnosticReasoner composite = new CompositeDiagnosticReasoner(deterministic);
		DiagnosticCheck check = check(GapCategory.EVALUATION_GAP, "False positive");

		RemediationReport report = composite.reason(diagnosticReport(check), emptyContext());

		assertThat(report.remediations()).hasSize(1);
		assertThat(report.remediations().get(0).actionType()).isEqualTo(ActionType.CALIBRATE_JUDGE);
		assertThat(report.proposals()).isEmpty();
	}

	@Test
	void withLlm_unresolvedForwardedToLlm() {
		// KB_GAP has no deterministic rule → will be unresolved → forwarded to LLM
		DiagnosticCheck kbCheck = check(GapCategory.KB_GAP, "Missing KB entry");

		LlmDiagnosticReasoner mockLlm = (unresolvedChecks, context) -> {
			RemediationAction action = new RemediationAction("knowledge-base", ActionType.ADD_KB_ENTRY,
					"Add entry for pattern X", "Details", Confidence.LLM_INFERRED, unresolvedChecks.get(0));
			RemediationProposal proposal = new RemediationProposal(ActionType.ADD_KB_ENTRY, "dependency-changes.md",
					"# New entry\n...", ProposalType.KB_ENTRY_DRAFT, Confidence.LLM_INFERRED, null);
			return new LlmReasoningResult(List.of(action), List.of(proposal));
		};

		CompositeDiagnosticReasoner composite = new CompositeDiagnosticReasoner(deterministic, mockLlm);
		RemediationReport report = composite.reason(diagnosticReport(kbCheck), emptyContext());

		assertThat(report.remediations()).hasSize(1);
		assertThat(report.remediations().get(0).confidence()).isEqualTo(Confidence.LLM_INFERRED);
		assertThat(report.proposals()).hasSize(1);
		assertThat(report.proposals().get(0).proposalType()).isEqualTo(ProposalType.KB_ENTRY_DRAFT);
		assertThat(report.unresolvedChecks()).isEmpty();
	}

	@Test
	void llmActionsAppendedAfterDeterministic() {
		// EG check → deterministic resolves it; KB check → goes to LLM
		DiagnosticCheck egCheck = check(GapCategory.EVALUATION_GAP, "False positive");
		DiagnosticCheck kbCheck = check(GapCategory.KB_GAP, "Missing KB entry");

		LlmDiagnosticReasoner mockLlm = (unresolvedChecks, context) -> {
			RemediationAction action = new RemediationAction("knowledge-base", ActionType.ADD_KB_ENTRY, "Add KB entry",
					"Details", Confidence.LLM_INFERRED, unresolvedChecks.get(0));
			return new LlmReasoningResult(List.of(action), List.of());
		};

		CompositeDiagnosticReasoner composite = new CompositeDiagnosticReasoner(deterministic, mockLlm);
		RemediationReport report = composite.reason(diagnosticReport(egCheck, kbCheck), emptyContext());

		assertThat(report.remediations()).hasSize(2);
		assertThat(report.remediations().get(0).confidence()).isEqualTo(Confidence.HEURISTIC); // deterministic
		assertThat(report.remediations().get(1).confidence()).isEqualTo(Confidence.LLM_INFERRED); // LLM
	}

	@Test
	void emptyUnresolved_llmNotCalled() {
		DiagnosticCheck egCheck = check(GapCategory.EVALUATION_GAP, "False positive");

		// LLM that would throw if called
		LlmDiagnosticReasoner mockLlm = (unresolvedChecks, context) -> {
			throw new AssertionError("LLM should not be called when no unresolved checks");
		};

		CompositeDiagnosticReasoner composite = new CompositeDiagnosticReasoner(deterministic, mockLlm);
		RemediationReport report = composite.reason(diagnosticReport(egCheck), emptyContext());

		assertThat(report.remediations()).hasSize(1);
	}

	// --- helpers ---

	private static DiagnosticCheck check(GapCategory category, String rationale) {
		return new DiagnosticCheck("TestJudge", Check.fail("test_check", "failure"), category, rationale);
	}

	private static ReasoningContext emptyContext() {
		return new ReasoningContext(null, null, Set.of(), List.of(), null, null, List.of(), null, null);
	}

	private static DiagnosticReport diagnosticReport(DiagnosticCheck... checks) {
		List<DiagnosticCheck> checkList = List.of(checks);
		ItemDiagnostic item = new ItemDiagnostic("item-1", checkList,
				checkList.isEmpty() ? null : checks[0].gapCategory());
		return new DiagnosticReport("exp-1", List.of(item), GapDistribution.fromChecks(checkList), List.of());
	}

}
