package io.github.markpollack.experiment.judge;

import java.util.List;

import io.github.markpollack.experiment.attestation.ItemAccounting;
import io.github.markpollack.experiment.attestation.SubjectOutcome;
import io.github.markpollack.experiment.result.ExperimentResult;
import io.github.markpollack.experiment.result.ItemCounts;
import org.jspecify.annotations.Nullable;

/**
 * Typed wrapper around {@link ExperimentResult} for judge experiments. Exposes
 * judge-specific conveniences while preserving compatibility with {@code ResultStore} and
 * {@code ComparisonEngine}.
 *
 * @param experimentResult the underlying experiment result
 * @param agreementRate fraction of scored items where the judge agreed with the expected
 * label, or <b>null when nothing was scored</b>. An experiment that scored nothing did
 * not disagree with everything, and reporting 0.0 for it would put it on a chart beside a
 * judge that got every item wrong
 * @param disagreements list of items where judge disagreed
 */
public record JudgeExperimentResult(ExperimentResult experimentResult, @Nullable Double agreementRate,
		List<JudgeDisagreement> disagreements) {

	/**
	 * Create from an {@link ExperimentResult} containing {@link JudgeExecutionDetail}
	 * items.
	 * @param result the experiment result
	 * @return a judge-typed result with computed agreement rate and disagreement list
	 */
	public static JudgeExperimentResult from(ExperimentResult result) {
		if (result.recordingSchemaVersion() != null && "JUDGE".equals(result.experimentKind())) {
			io.github.markpollack.experiment.recording.RecordingValidation.validate(result);
			var comparisons = result.items()
				.stream()
				.map(i -> (JudgeExecutionDetail) i.executionDetail())
				.map(JudgeExecutionDetail::comparison)
				.filter(c -> c != null && c.scored())
				.toList();
			long matches = comparisons.stream().filter(c -> Boolean.TRUE.equals(c.match())).count();
			Double agreement = comparisons.isEmpty() || !"COMPLETE".equals(result.runState()) ? null
					: (double) matches / comparisons.size();
			var disagreements = result.items()
				.stream()
				.filter(i -> i.executionDetail() instanceof JudgeExecutionDetail d && d.comparison() != null
						&& d.comparison().scored() && Boolean.FALSE.equals(d.comparison().match()))
				.map(i -> new JudgeDisagreement(i.itemId(), (JudgeExecutionDetail) i.executionDetail()))
				.toList();
			return new JudgeExperimentResult(result, agreement, disagreements);
		}
		// Read from the recorded verdict, not from the stored flag. A stored flag can
		// contradict its own evidence — an old item can carry passed=false with no
		// verdict
		// at all — and trusting it here is how an item nobody scored became a
		// disagreement and dragged the rate down with it.
		List<JudgeDisagreement> disagreements = result.items()
			.stream()
			.filter(item -> ItemAccounting.outcomeOf(item) == SubjectOutcome.NON_PASS)
			.filter(item -> item.executionDetail() instanceof JudgeExecutionDetail)
			.map(item -> new JudgeDisagreement(item.itemId(), (JudgeExecutionDetail) item.executionDetail()))
			.toList();

		ItemCounts counts = ItemAccounting.count(result.items());
		Double agreement = counts.passRate().isPresent() ? counts.passRate().getAsDouble() : null;

		return new JudgeExperimentResult(result, agreement, disagreements);
	}

	/**
	 * Mean comparison score, separate from Boolean agreement. Null for
	 * incomplete/all-unscored runs.
	 */
	public @Nullable Double meanScore() {
		if (!"COMPLETE".equals(experimentResult.runState()) || experimentResult.judgeRun() == null)
			return null;
		return experimentResult.aggregateScores().get(experimentResult.judgeRun().measureId());
	}

	/** Number of scored comparisons; unscored rows never enter the denominator. */
	public long scoredCount() {
		return experimentResult.items()
			.stream()
			.filter(i -> i.executionDetail() instanceof JudgeExecutionDetail d && d.comparison() != null
					&& d.comparison().scored())
			.count();
	}

	public long unscoredCount() {
		return experimentResult.items().size() - scoredCount();
	}

	/** Explicitly partial diagnostic, without claiming a complete-run rate. */
	public @Nullable Double partialAgreementRate() {
		long scored = scoredCount();
		if (scored == 0)
			return null;
		return (double) experimentResult.items()
			.stream()
			.filter(i -> i.executionDetail() instanceof JudgeExecutionDetail d && d.comparison() != null
					&& d.comparison().scored() && Boolean.TRUE.equals(d.comparison().match()))
			.count() / scored;
	}

	/** For {@code ComparisonEngine} and {@code ResultStore} compatibility. */
	public ExperimentResult asExperimentResult() {
		return experimentResult;
	}

}
