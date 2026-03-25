package io.github.markpollack.experiment.diagnostic;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.markpollack.journal.claude.PhaseCapture;
import io.github.markpollack.journal.claude.ToolResultRecord;
import io.github.markpollack.journal.claude.ToolUseRecord;
import org.jspecify.annotations.Nullable;

import io.github.markpollack.experiment.pipeline.AnalysisEnvelope;
import io.github.markpollack.experiment.pipeline.ExecutionPlan;

/**
 * The full "data menu" available to the diagnostic reasoning pipeline. Structured data
 * for deterministic rules, trajectory exhaust for pattern detection, and file pointers
 * for JIT LLM navigation into details.
 *
 * @param analysis static analysis results (import patterns, dependencies, metadata)
 * @param plan execution plan (tool recommendations, roadmap, KB files read)
 * @param availableTools all tools that could have been selected by the planner
 * @param phases full per-phase captures: thinking, tool calls, results, text
 * @param runDir root results directory for this run
 * @param runLog path to the run.log file (full SLF4J output)
 * @param traceFiles agent-trace-*.jsonl per-event JSONL traces
 * @param workspacePath preserved workspace (final state after agent)
 * @param resultJsonPath the persisted ExperimentResult JSON
 */
public record ReasoningContext(@Nullable AnalysisEnvelope analysis, @Nullable ExecutionPlan plan,
		Set<String> availableTools, List<PhaseCapture> phases, @Nullable Path runDir, @Nullable Path runLog,
		List<Path> traceFiles, @Nullable Path workspacePath, @Nullable Path resultJsonPath) {

	public ReasoningContext {
		availableTools = Collections.unmodifiableSet(new LinkedHashSet<>(availableTools));
		phases = List.copyOf(phases);
		traceFiles = List.copyOf(traceFiles);
	}

	/**
	 * Tools available but not selected by the planner.
	 */
	public Set<String> unusedTools() {
		if (plan == null || plan.toolRecommendations() == null) {
			return availableTools;
		}
		Set<String> unused = new LinkedHashSet<>(availableTools);
		unused.removeAll(plan.toolRecommendations());
		return Collections.unmodifiableSet(unused);
	}

	/**
	 * All tool results where {@code isError} is true, across all phases.
	 */
	public List<ToolResultRecord> errorToolResults() {
		List<ToolResultRecord> errors = new ArrayList<>();
		for (PhaseCapture phase : phases) {
			if (phase.toolResults() != null) {
				for (ToolResultRecord result : phase.toolResults()) {
					if (result.isError()) {
						errors.add(result);
					}
				}
			}
		}
		return Collections.unmodifiableList(errors);
	}

	/**
	 * All tool uses matching the given tool name, across all phases.
	 */
	public List<ToolUseRecord> toolUsesByName(String name) {
		List<ToolUseRecord> matches = new ArrayList<>();
		for (PhaseCapture phase : phases) {
			if (phase.toolUses() != null) {
				for (ToolUseRecord use : phase.toolUses()) {
					if (name.equals(use.name())) {
						matches.add(use);
					}
				}
			}
		}
		return Collections.unmodifiableList(matches);
	}

}
