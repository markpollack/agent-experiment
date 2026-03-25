package io.github.markpollack.experiment.agent;

import java.util.List;
import java.util.Map;

import io.github.markpollack.journal.claude.PhaseCapture;
import io.github.markpollack.journal.claude.ToolUseRecord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InvocationResultTest {

	@Test
	void completedFactoryCreatesSuccessfulResult() {
		InvocationResult result = InvocationResult.completed(List.of(), 1000, 500, 200, 0.05, 3000, "session-1",
				Map.of("itemId", "ITEM-001"));

		assertThat(result.success()).isTrue();
		assertThat(result.status()).isEqualTo(TerminalStatus.COMPLETED);
		assertThat(result.phases()).isEmpty();
		assertThat(result.inputTokens()).isEqualTo(1000);
		assertThat(result.outputTokens()).isEqualTo(500);
		assertThat(result.thinkingTokens()).isEqualTo(200);
		assertThat(result.totalCostUsd()).isEqualTo(0.05);
		assertThat(result.durationMs()).isEqualTo(3000);
		assertThat(result.sessionId()).isEqualTo("session-1");
		assertThat(result.errorMessage()).isNull();
	}

	@Test
	void totalTokensSumsAllCategories() {
		InvocationResult result = InvocationResult.completed(List.of(), 1000, 500, 200, 0.05, 3000, null, Map.of());

		assertThat(result.totalTokens()).isEqualTo(1700);
	}

	@Test
	void timeoutFactoryCreatesFailedResult() {
		InvocationResult result = InvocationResult.timeout(30000, Map.of(), "Exceeded 30s timeout");

		assertThat(result.success()).isFalse();
		assertThat(result.status()).isEqualTo(TerminalStatus.TIMEOUT);
		assertThat(result.phases()).isEmpty();
		assertThat(result.durationMs()).isEqualTo(30000);
		assertThat(result.errorMessage()).isEqualTo("Exceeded 30s timeout");
		assertThat(result.inputTokens()).isZero();
	}

	@Test
	void errorFactoryCreatesFailedResult() {
		InvocationResult result = InvocationResult.error("Connection refused", Map.of("itemId", "ITEM-002"));

		assertThat(result.success()).isFalse();
		assertThat(result.status()).isEqualTo(TerminalStatus.ERROR);
		assertThat(result.phases()).isEmpty();
		assertThat(result.errorMessage()).isEqualTo("Connection refused");
		assertThat(result.metadata()).containsEntry("itemId", "ITEM-002");
	}

	@Test
	void phasesListIsDefensiveCopy() {
		var mutablePhases = new java.util.ArrayList<io.github.markpollack.journal.claude.PhaseCapture>();

		InvocationResult result = InvocationResult.completed(mutablePhases, 0, 0, 0, 0.0, 0, null, Map.of());

		mutablePhases.add(null); // would fail if not defensive copy
		assertThat(result.phases()).isEmpty();
	}

	@Test
	void fromPhasesAggregatesTokensAndCost() {
		PhaseCapture phase1 = createPhase("phase1", 100, 50, 20, 0.01, false);
		PhaseCapture phase2 = createPhase("phase2", 200, 80, 30, 0.02, false);

		InvocationResult result = InvocationResult.fromPhases(List.of(phase1, phase2), 5000, null, Map.of());

		assertThat(result.success()).isTrue();
		assertThat(result.status()).isEqualTo(TerminalStatus.COMPLETED);
		assertThat(result.inputTokens()).isEqualTo(300);
		assertThat(result.outputTokens()).isEqualTo(130);
		assertThat(result.thinkingTokens()).isEqualTo(50);
		assertThat(result.totalCostUsd()).isCloseTo(0.03, org.assertj.core.data.Offset.offset(0.0001));
		assertThat(result.durationMs()).isEqualTo(5000);
		assertThat(result.phases()).hasSize(2);
	}

	@Test
	void fromPhasesExtractsSessionIdFromLastPhase() {
		PhaseCapture phase = createPhaseWithSessionId("invoke", 100, 50, 0, 0.01, false, "session-abc");

		InvocationResult result = InvocationResult.fromPhases(List.of(phase), 3000, null, Map.of());

		assertThat(result.sessionId()).isEqualTo("session-abc");
	}

	@Test
	void fromPhasesUsesProvidedSessionId() {
		PhaseCapture phase = createPhaseWithSessionId("invoke", 100, 50, 0, 0.01, false, "session-from-phase");

		InvocationResult result = InvocationResult.fromPhases(List.of(phase), 3000, "explicit-session", Map.of());

		assertThat(result.sessionId()).isEqualTo("explicit-session");
	}

	@Test
	void fromPhasesDetectsError() {
		PhaseCapture errorPhase = createPhase("invoke", 100, 50, 0, 0.005, true);

		InvocationResult result = InvocationResult.fromPhases(List.of(errorPhase), 2000, null,
				Map.of("itemId", "ITEM-001"));

		assertThat(result.success()).isFalse();
		assertThat(result.status()).isEqualTo(TerminalStatus.ERROR);
		assertThat(result.errorMessage()).isNotNull();
		assertThat(result.inputTokens()).isEqualTo(100);
		assertThat(result.metadata()).containsEntry("itemId", "ITEM-001");
	}

	@Test
	void fromPhasesEmptyList() {
		InvocationResult result = InvocationResult.fromPhases(List.of(), 0, null, Map.of());

		assertThat(result.success()).isTrue();
		assertThat(result.status()).isEqualTo(TerminalStatus.COMPLETED);
		assertThat(result.inputTokens()).isZero();
		assertThat(result.outputTokens()).isZero();
		assertThat(result.totalCostUsd()).isZero();
		assertThat(result.sessionId()).isNull();
		assertThat(result.phases()).isEmpty();
	}

	@Test
	void totalInputTokensIncludesCacheTokens() {
		InvocationResult result = new InvocationResult(true, TerminalStatus.COMPLETED, List.of(), 14, 200, 0, 5000,
				80000, 0.05, 3000, "sess-1", Map.of(), null, null, null);

		assertThat(result.cacheCreationInputTokens()).isEqualTo(5000);
		assertThat(result.cacheReadInputTokens()).isEqualTo(80000);
		assertThat(result.totalInputTokens()).isEqualTo(85014); // 14 + 5000 + 80000
		assertThat(result.totalTokens()).isEqualTo(85214); // 85014 + 200 + 0
	}

	@Test
	void fromPhasesAggregatesCacheTokens() {
		PhaseCapture phase1 = new PhaseCapture("phase1", "prompt", 10, 100, 0, 2000, 30000, 1000, 800, 0.01, "sess", 2,
				false, "output", List.of(), List.of(), null, null);
		PhaseCapture phase2 = new PhaseCapture("phase2", "prompt", 5, 80, 0, 1000, 20000, 1000, 800, 0.01, "sess", 2,
				false, "output", List.of(), List.of(), null, null);

		InvocationResult result = InvocationResult.fromPhases(List.of(phase1, phase2), 5000, null, Map.of());

		assertThat(result.cacheCreationInputTokens()).isEqualTo(3000); // 2000 + 1000
		assertThat(result.cacheReadInputTokens()).isEqualTo(50000); // 30000 + 20000
		assertThat(result.inputTokens()).isEqualTo(15); // 10 + 5
		assertThat(result.totalInputTokens()).isEqualTo(53015); // 15 + 3000 + 50000
	}

	private static PhaseCapture createPhase(String name, int input, int output, int thinking, double cost,
			boolean isError) {
		return createPhaseWithSessionId(name, input, output, thinking, cost, isError, null);
	}

	private static PhaseCapture createPhaseWithSessionId(String name, int input, int output, int thinking, double cost,
			boolean isError, String sessionId) {
		return new PhaseCapture(name, "prompt", input, output, thinking, 1000, 800, cost, sessionId, 5, isError,
				"text output", List.of(), List.of(), null);
	}

}
