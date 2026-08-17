package io.github.markpollack.experiment.diagnostic;

import java.util.List;

import io.github.markpollack.experiment.pipeline.AnalysisEnvelope;
import io.github.markpollack.experiment.pipeline.ExecutionPlan;
import io.github.markpollack.experiment.result.RecordedVerdict;
import org.jspecify.annotations.Nullable;

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
	List<DiagnosticCheck> classify(RecordedVerdict verdict, @Nullable AnalysisEnvelope analysis,
			@Nullable ExecutionPlan plan);

	/**
	 * Classify a live verdict after projecting it to the stable recorded representation.
	 */
	default List<DiagnosticCheck> classify(io.github.markpollack.judge.jury.Verdict verdict,
			@Nullable AnalysisEnvelope analysis, @Nullable ExecutionPlan plan) {
		return classify(RecordedVerdict.from(verdict), analysis, plan);
	}

}
