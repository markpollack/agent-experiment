package io.github.markpollack.experiment.comparison;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.markpollack.experiment.result.ExperimentResult;

/**
 * Compares experiment runs and produces comparison results. All computation is local (no
 * server dependency).
 *
 * <p>
 * Patterns: Braintrust ScoreSummary + baseline cascade, Weave auto_summarize for
 * type-aware aggregation.
 */
public interface ComparisonEngine {

	/** Compare an experiment against a baseline. */
	ComparisonResult compare(ExperimentResult current, ExperimentResult baseline);

	/** Summarize a single experiment (no comparison). */
	ExperimentSummary summarize(ExperimentResult experiment);

	/**
	 * Resolve baseline using cascade: 1. Explicit baseline ID 2. "baselineId" in
	 * experiment metadata 3. Most recent prior experiment with same name.
	 */
	Optional<ExperimentResult> resolveBaseline(ExperimentResult current, Optional<String> explicitBaselineId);

	/** Detect regressions exceeding per-scorer thresholds. */
	List<ItemDiff> detectRegressions(ComparisonResult comparison, Map<String, Double> thresholds);

}
