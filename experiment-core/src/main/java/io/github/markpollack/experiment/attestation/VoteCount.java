package io.github.markpollack.experiment.attestation;

import java.util.Map;

import io.github.markpollack.experiment.result.RecordedJudgment;
import io.github.markpollack.experiment.result.RecordedJudgmentStatus;
import io.github.markpollack.judge.jury.AggregationEvidence;
import io.github.markpollack.judge.result.Judgment;
import org.jspecify.annotations.Nullable;

/**
 * The vote counts one jury recorded for one item, read from its stored aggregation
 * evidence.
 *
 * <p>
 * Every count is nullable, and null means <em>not recorded</em>. It never means zero: a
 * jury that recorded {@code errorCount 0} and a jury that recorded nothing are different
 * facts, and rendering the second as the first is how an absence passes for a
 * measurement.
 *
 * @param scope {@code "jury"} for the top-level jury, otherwise the composite attempt
 * name (a cascade tier or a meta-jury member)
 * @param relation composite relation wire name, or null for the top-level jury
 * @param outcome the status this jury reached, or null when it failed to execute
 * @param failureCode why a composite attempt produced no verdict, or null when it did
 * @param strategy voting strategy that reduced the votes, when recorded
 * @param errorPolicy error policy applied, when recorded
 * @param inputCount judgments submitted, when recorded
 * @param eligibleCount judgments that contributed to the reduction, when recorded
 * @param explicitAbstainCount judgments that abstained on their own, when recorded
 * @param errorCount judgments that errored, when recorded
 */
public record VoteCount(String scope, @Nullable String relation, @Nullable RecordedJudgmentStatus outcome,
		@Nullable String failureCode, @Nullable String strategy, @Nullable String errorPolicy,
		@Nullable Integer inputCount, @Nullable Integer eligibleCount, @Nullable Integer explicitAbstainCount,
		@Nullable Integer errorCount) {

	public VoteCount {
		java.util.Objects.requireNonNull(scope, "scope must not be null");
	}

	/**
	 * True when this jury recorded how many judgments it received and how many counted.
	 */
	public boolean hasEvidence() {
		return this.inputCount != null && this.eligibleCount != null && this.errorCount != null;
	}

	static VoteCount of(String scope, @Nullable String relation, RecordedJudgment aggregated) {
		Map<?, ?> evidence = aggregated.metadata().get(Judgment.AGGREGATION_KEY) instanceof Map<?, ?> map ? map
				: Map.of();
		return new VoteCount(scope, relation, aggregated.status(), null, text(evidence, AggregationEvidence.STRATEGY),
				text(evidence, AggregationEvidence.ERROR_POLICY), count(evidence, AggregationEvidence.INPUT_COUNT),
				count(evidence, AggregationEvidence.ELIGIBLE_COUNT),
				count(evidence, AggregationEvidence.EXPLICIT_ABSTAIN_COUNT),
				count(evidence, AggregationEvidence.ERROR_COUNT));
	}

	static VoteCount failed(String scope, String relation, @Nullable String failureCode) {
		return new VoteCount(scope, relation, null, failureCode, null, null, null, null, null, null);
	}

	private static @Nullable String text(Map<?, ?> evidence, String key) {
		return evidence.get(key) instanceof String value ? value : null;
	}

	private static @Nullable Integer count(Map<?, ?> evidence, String key) {
		return evidence.get(key) instanceof Number value ? value.intValue() : null;
	}

}
