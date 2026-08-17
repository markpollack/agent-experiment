package io.github.markpollack.experiment.result;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;
import io.github.markpollack.judge.result.Judgment;

/**
 * Agent Experiment-owned persisted judgment representation.
 *
 * <p>
 * Runtime judges use Agent Judge's {@link Judgment}; experiment results record only this
 * normalized, dependency-independent projection.
 *
 * @param status stable outcome
 * @param score normalized measured score, or null when the judge made no measurement
 * @param label classification label, or null
 * @param reasoning human-readable explanation
 * @param checks recorded check evidence
 * @param metadata portable judgment metadata
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecordedJudgment(RecordedJudgmentStatus status, @Nullable Double score, @Nullable String label,
		String reasoning, List<RecordedCheck> checks, Map<String, Object> metadata) {

	public RecordedJudgment {
		java.util.Objects.requireNonNull(status, "status must not be null");
		java.util.Objects.requireNonNull(reasoning, "reasoning must not be null");
		checks = List.copyOf(checks);
		metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
		if (score != null && (!Double.isFinite(score) || score < 0.0 || score > 1.0)) {
			throw new IllegalArgumentException("score must be finite and between 0.0 and 1.0");
		}
	}

	public static RecordedJudgment from(Judgment judgment) {
		return new RecordedJudgment(RecordedJudgmentStatus.from(judgment.status()), judgment.score(), judgment.label(),
				judgment.reasoning(), judgment.checks().stream().map(RecordedCheck::from).toList(),
				judgment.metadata());
	}

	public boolean pass() {
		return this.status == RecordedJudgmentStatus.PASS;
	}

	public OptionalDouble effectiveScore() {
		if (this.score != null) {
			return OptionalDouble.of(this.score);
		}
		return switch (this.status) {
			case PASS -> OptionalDouble.of(1.0);
			case FAIL -> OptionalDouble.of(0.0);
			case ABSTAIN, ERROR -> OptionalDouble.empty();
		};
	}

}
