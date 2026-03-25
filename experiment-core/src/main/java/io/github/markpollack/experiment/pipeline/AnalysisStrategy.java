package io.github.markpollack.experiment.pipeline;

/**
 * Strategy for project analysis depth.
 */
public enum AnalysisStrategy {

	/**
	 * Lightweight: parse POM + scan Java files for import patterns. No external tools
	 * required.
	 */
	POM_ONLY,

	/**
	 * Full analysis: SCIP index + ASM annotation extraction. Requires compiled classes
	 * and SCIP binary. Deferred — not yet implemented.
	 */
	FULL

}
