package io.github.markpollack.experiment.agent;

import java.util.List;
import java.util.Map;

import io.github.markpollack.journal.claude.PhaseCapture;
import io.github.markpollack.journal.claude.ToolResultRecord;
import io.github.markpollack.journal.claude.ToolUseRecord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolTelemetryTest {

	@Test
	void fromPhasesTalliesCountsBashAndTurns() {
		PhaseCapture phase = phase(3,
				List.of(use("Read", Map.of("file_path", "/a")), use("Read", Map.of("file_path", "/b")),
						use("Bash", Map.of("command", "ls -la")), use("Bash", Map.of("command", "git status"))),
				List.of());

		ToolTelemetry telemetry = ToolTelemetry.fromPhases(List.of(phase));

		assertThat(telemetry.numTurns()).isEqualTo(3);
		assertThat(telemetry.toolCallCounts()).containsEntry("Read", 2).containsEntry("Bash", 2);
		assertThat(telemetry.bashCommands()).containsExactly("ls -la", "git status");
		assertThat(telemetry.totalToolCalls()).isEqualTo(4);
		assertThat(telemetry.permissionDenials()).isZero();
	}

	@Test
	void fromPhasesAggregatesAcrossPhases() {
		PhaseCapture first = phase(2, List.of(use("Read", Map.of())), List.of());
		PhaseCapture second = phase(1, List.of(use("Read", Map.of()), use("Write", Map.of())), List.of());

		ToolTelemetry telemetry = ToolTelemetry.fromPhases(List.of(first, second));

		assertThat(telemetry.numTurns()).isEqualTo(3);
		assertThat(telemetry.toolCallCounts()).containsEntry("Read", 2).containsEntry("Write", 1);
	}

	@Test
	void fromPhasesCountsPermissionDenials() {
		PhaseCapture phase = phase(1, List.of(use("Bash", Map.of("command", "rm -rf /"))),
				List.of(new ToolResultRecord("t1", "This tool is not allowed by the current settings", true),
						new ToolResultRecord("t2", "ok", false)));

		ToolTelemetry telemetry = ToolTelemetry.fromPhases(List.of(phase));

		assertThat(telemetry.permissionDenials()).isEqualTo(1);
	}

	@Test
	void fromPhasesHandlesNullToolRecords() {
		PhaseCapture phase = new PhaseCapture("invoke", "prompt", 10, 20, 0, 100, 100, 0.0, "s", 1, false, "out",
				List.of(), null, "result");

		ToolTelemetry telemetry = ToolTelemetry.fromPhases(List.of(phase));

		assertThat(telemetry.numTurns()).isEqualTo(1);
		assertThat(telemetry.toolCallCounts()).isEmpty();
		assertThat(telemetry.bashCommands()).isEmpty();
		assertThat(telemetry.permissionDenials()).isZero();
	}

	@Test
	void invocationResultExposesToolTelemetry() {
		PhaseCapture phase = phase(2, List.of(use("Read", Map.of()), use("Bash", Map.of("command", "echo hi"))),
				List.of());
		InvocationResult result = InvocationResult.fromPhases(List.of(phase), 1000, "session", Map.of());

		assertThat(result.toolTelemetry().totalToolCalls()).isEqualTo(2);
		assertThat(result.toolTelemetry().bashCommands()).containsExactly("echo hi");
	}

	@Test
	void isPermissionDenialOnlyMatchesErrorsWithMarkers() {
		assertThat(ToolTelemetry.isPermissionDenial(new ToolResultRecord("t", "permission denied", true))).isTrue();
		assertThat(ToolTelemetry.isPermissionDenial(new ToolResultRecord("t", "you haven't granted access", true)))
			.isTrue();
		assertThat(ToolTelemetry.isPermissionDenial(new ToolResultRecord("t", "permission denied", false))).isFalse();
		assertThat(ToolTelemetry.isPermissionDenial(new ToolResultRecord("t", "file not found", true))).isFalse();
	}

	private static ToolUseRecord use(String name, Map<String, Object> input) {
		return new ToolUseRecord("id-" + name, name, input);
	}

	private static PhaseCapture phase(int numTurns, List<ToolUseRecord> toolUses, List<ToolResultRecord> toolResults) {
		return new PhaseCapture("invoke", "prompt", 10, 20, 0, 0, 0, 100, 100, 0.0, "session", numTurns, false,
				"output", List.of(), toolUses, "result", toolResults);
	}

}
