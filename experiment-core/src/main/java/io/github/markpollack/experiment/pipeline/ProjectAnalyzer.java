package io.github.markpollack.experiment.pipeline;

import java.nio.file.Path;

/**
 * Deterministic analysis of a project workspace. No AI calls. Produces a structured
 * {@link AnalysisEnvelope} used by the {@link PlanGenerator}.
 *
 * <p>
 * Contract:
 * <ul>
 * <li>Deterministic: identical workspace + config produce identical envelope</li>
 * <li>No AI calls, no network calls</li>
 * <li>Read-only: does not modify workspace files</li>
 * </ul>
 */
public interface ProjectAnalyzer {

	/**
	 * Analyze the project at the given workspace path.
	 * @param workspace root directory of the project to analyze
	 * @param config analysis configuration (strategy, target versions)
	 * @return structured analysis of the project
	 * @throws AnalysisException for I/O failures or malformed project files
	 */
	AnalysisEnvelope analyze(Path workspace, AnalysisConfig config);

}
