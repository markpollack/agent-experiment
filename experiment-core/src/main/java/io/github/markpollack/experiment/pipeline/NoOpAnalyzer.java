package io.github.markpollack.experiment.pipeline;

import java.nio.file.Path;
import java.util.Map;

/**
 * Stub {@link ProjectAnalyzer} for non-Maven consumer projects. Returns a minimal
 * {@link AnalysisEnvelope} with project name derived from the workspace directory and
 * build tool set to "none". All Spring/Maven-specific fields are null or empty.
 */
public class NoOpAnalyzer implements ProjectAnalyzer {

	@Override
	public AnalysisEnvelope analyze(Path workspace, AnalysisConfig config) {
		long startMs = System.currentTimeMillis();
		return AnalysisEnvelope.builder()
			.projectName(workspace.getFileName().toString())
			.buildTool("none")
			.metadata(Map.of("analysisDurationMs", System.currentTimeMillis() - startMs))
			.build();
	}

}
