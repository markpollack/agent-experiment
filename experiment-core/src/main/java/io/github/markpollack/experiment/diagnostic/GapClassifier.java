package io.github.markpollack.experiment.diagnostic;

import java.util.List;

import io.github.markpollack.experiment.pipeline.AnalysisEnvelope;
import io.github.markpollack.experiment.pipeline.ExecutionPlan;
import org.jspecify.annotations.Nullable;
import org.springaicommunity.judge.jury.Verdict;

/**
 * Classifies judge failures into structured {@link GapCategory} values.
 *
 * <p>
 * Gap classification is post-hoc — applied after the jury produces verdicts. The
 * classifier examines each failed check in the context of the analysis and execution plan
 * to determine where in the pipeline the failure originated.
 */
public interface GapClassifier {

	/**
	 * Classify failed checks in a verdict into gap categories.
	 * @param verdict the jury verdict to classify
	 * @param analysis the pipeline analysis envelope (nullable)
	 * @param plan the pipeline execution plan (nullable)
	 * @return diagnostic checks with gap classifications
	 */
	List<DiagnosticCheck> classify(Verdict verdict, @Nullable AnalysisEnvelope analysis, @Nullable ExecutionPlan plan);

}
