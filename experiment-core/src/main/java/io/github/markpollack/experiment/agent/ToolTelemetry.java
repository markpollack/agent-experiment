package io.github.markpollack.experiment.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.markpollack.journal.claude.PhaseCapture;
import io.github.markpollack.journal.claude.ToolResultRecord;
import io.github.markpollack.journal.claude.ToolUseRecord;

/**
 * First-class, typed tally of tool activity across an invocation's phases.
 *
 * <p>
 * Derived from the {@link PhaseCapture} tool records the agent SDK already produces,
 * promoting per-tool call counts, the bash-command breakdown, conversation turns, and the
 * permission-denial count out of stringly-typed metadata.
 *
 * @param numTurns total conversation turns across all phases
 * @param toolCallCounts call count per tool name (e.g. {@code {"Read": 4, "Bash": 2}})
 * @param bashCommands the command string of every {@code Bash} tool call, in order
 * @param permissionDenials number of tool results that look like a permission denial
 */
public record ToolTelemetry(int numTurns, Map<String, Integer> toolCallCounts, List<String> bashCommands,
		int permissionDenials) {

	public ToolTelemetry {
		toolCallCounts = Map.copyOf(toolCallCounts);
		bashCommands = List.copyOf(bashCommands);
	}

	/** Total tool calls across all tools. */
	public int totalToolCalls() {
		return toolCallCounts.values().stream().mapToInt(Integer::intValue).sum();
	}

	/**
	 * Aggregates tool telemetry from a list of phase captures. Null tool-record lists
	 * (older captures) are treated as empty.
	 */
	public static ToolTelemetry fromPhases(List<PhaseCapture> phases) {
		int turns = 0;
		Map<String, Integer> counts = new LinkedHashMap<>();
		List<String> bash = new java.util.ArrayList<>();
		int denials = 0;

		for (PhaseCapture phase : phases) {
			turns += phase.numTurns();
			if (phase.toolUses() != null) {
				for (ToolUseRecord use : phase.toolUses()) {
					counts.merge(use.name(), 1, Integer::sum);
					if ("Bash".equals(use.name()) && use.input() != null) {
						Object command = use.input().get("command");
						if (command != null) {
							bash.add(command.toString());
						}
					}
				}
			}
			if (phase.toolResults() != null) {
				for (ToolResultRecord result : phase.toolResults()) {
					if (isPermissionDenial(result)) {
						denials++;
					}
				}
			}
		}

		return new ToolTelemetry(turns, counts, bash, denials);
	}

	/**
	 * Best-effort detection of a permission denial. A tool result counts as a denial when
	 * it is flagged as an error and its content mentions a permission/allow-list refusal.
	 * Heuristic — matches the markers the Claude CLI emits when a tool is blocked (e.g.
	 * via {@code --disallowedTools} or an ungranted permission).
	 */
	static boolean isPermissionDenial(ToolResultRecord result) {
		if (!result.isError() || result.content() == null) {
			return false;
		}
		String content = result.content().toLowerCase(java.util.Locale.ROOT);
		return content.contains("permission") || content.contains("haven't granted") || content.contains("not allowed")
				|| content.contains("disallowed");
	}

}
