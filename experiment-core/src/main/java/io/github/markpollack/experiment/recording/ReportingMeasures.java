package io.github.markpollack.experiment.recording;

import java.util.Map;
import java.util.LinkedHashMap;
import io.github.markpollack.experiment.result.ExperimentResult;
import io.github.markpollack.experiment.judge.JudgeExperimentResult;
import io.github.markpollack.experiment.judge.JudgeExecutionDetail;
import org.jspecify.annotations.Nullable;

/**
 * Named reporting policies and comparison population beside operational outcome counts.
 */
public record ReportingMeasures(String verdictRole, String countRule, @Nullable String comparisonRule,
		@Nullable String measureId, String runState, @Nullable Long scored, @Nullable Long unscored,
		@Nullable Double agreementRate, @Nullable Double meanScore, Map<String, Long> unscoredReasons) {
	public ReportingMeasures {
		unscoredReasons = Map.copyOf(unscoredReasons);
	}

	public static ReportingMeasures of(ExperimentResult result) {
		RecordingValidation.validate(result);
		if (result.recordingSchemaVersion() == null)
			return new ReportingMeasures("LEGACY", "legacy-recorded", "legacy-agreement", null, "LEGACY", null, null,
					null, null, Map.of());
		if (!"JUDGE".equals(result.experimentKind()))
			return new ReportingMeasures("SUBJECT", "subject-outcome:v1", null, null, result.runState(), null, null,
					null, null, Map.of());
		var typed = JudgeExperimentResult.from(result);
		Map<String, Long> reasons = new LinkedHashMap<>();
		result.items().forEach(item -> {
			var comparison = ((JudgeExecutionDetail) item.executionDetail()).comparison();
			if (!comparison.scored())
				reasons.merge(comparison.unscoredReason(), 1L, Long::sum);
		});
		return new ReportingMeasures("CANDIDATE", result.judgeRun().countRule(), result.judgeRun().comparisonRule(),
				result.judgeRun().measureId(), result.runState(), typed.scoredCount(), typed.unscoredCount(),
				typed.agreementRate(), typed.meanScore(), reasons);
	}
}
