package io.github.markpollack.experiment.judge;

import java.util.List;
import io.github.markpollack.experiment.result.ExecutionDetail;
import io.github.markpollack.experiment.result.RecordedJudgment;
import io.github.markpollack.experiment.recording.*;
import org.jspecify.annotations.Nullable;

/** Current row evidence; legacy three-field constructors remain compatibility inputs. */
public record JudgeExecutionDetail(String kind, int schemaVersion, @Nullable RowIdentity row,
		@Nullable CaseInputIdentity caseInputIdentity, String rowState, String invocationState,
		@Nullable RecordedJudgment candidateJudgment, @Nullable JudgeTruth truth, @Nullable JudgeComparison comparison,
		@Nullable InvocationObservation observation, List<FailureFact> failures, @Nullable String expectedLabel,
		@Nullable JudgeScorerResult scorerResult) implements ExecutionDetail {
	public JudgeExecutionDetail {
		failures = List.copyOf(failures);
	}

	public JudgeExecutionDetail(RecordedJudgment candidateJudgment, String expectedLabel,
			JudgeScorerResult scorerResult) {
		this("LEGACY_JUDGE", 0, null, null, "LEGACY", "LEGACY", candidateJudgment, null, null, null, List.of(),
				expectedLabel, scorerResult);
	}

	public JudgeExecutionDetail(io.github.markpollack.judge.judgment.Judgment candidate, String expected,
			JudgeScorerResult result) {
		this(RecordedJudgment.from(candidate), expected, result);
	}

	public static JudgeExecutionDetail current(RowIdentity row, @Nullable CaseInputIdentity identity, String rowState,
			String invocationState, @Nullable RecordedJudgment candidate, @Nullable JudgeTruth truth,
			JudgeComparison comparison, @Nullable InvocationObservation observation, List<FailureFact> failures) {
		return new JudgeExecutionDetail("JUDGE_EVALUATION", 1, row, identity, rowState, invocationState, candidate,
				truth, comparison, observation, failures, truth == null ? null : truth.label(),
				new JudgeScorerResult(comparison.match(), comparison.score(), comparison.reasoning()));
	}
}
