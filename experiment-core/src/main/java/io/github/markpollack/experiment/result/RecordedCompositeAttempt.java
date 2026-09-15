package io.github.markpollack.experiment.result;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;
import io.github.markpollack.judge.jury.CompositeAttempt;

/**
 * Agent Experiment-owned persisted form of a composite jury attempt.
 *
 * <p>
 * {@code disposition} says whether the parent could use what this stage returned: USED,
 * or STAGE_FAILED with a {@code dispositionReason}. It is recorded even when a later tier
 * went on to pass, because a stage that failed is an instrument failure whether or not
 * the cascade recovered, and a count of them is not recoverable from the outcome alone.
 *
 * @param name tier or member name
 * @param relation how this attempt relates to its parent
 * @param policy the tier policy, when there is one
 * @param disposition USED or STAGE_FAILED, or null when not recorded
 * @param dispositionReason why a stage failed, when it did
 * @param verdict what the stage returned, kept even when the stage failed
 * @param failureCode set when the stage produced no verdict at all
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecordedCompositeAttempt(String name, String relation, @Nullable String policy,
		@Nullable String disposition, @Nullable String dispositionReason, @Nullable RecordedVerdict verdict,
		@Nullable String failureCode) {

	public RecordedCompositeAttempt {
		java.util.Objects.requireNonNull(name, "name must not be null");
		java.util.Objects.requireNonNull(relation, "relation must not be null");
		if ((verdict == null) == (failureCode == null)) {
			throw new IllegalArgumentException("exactly one of verdict or failureCode must be present");
		}
	}

	/** An attempt recorded before dispositions existed. */
	public RecordedCompositeAttempt(String name, String relation, @Nullable String policy,
			@Nullable RecordedVerdict verdict, @Nullable String failureCode) {
		this(name, relation, policy, null, null, verdict, failureCode);
	}

	public static RecordedCompositeAttempt from(CompositeAttempt attempt) {
		return new RecordedCompositeAttempt(attempt.name(), attempt.relation().wireName(),
				attempt.policy() != null ? attempt.policy().wireName() : null,
				attempt.disposition() != null ? attempt.disposition().wireName() : null,
				attempt.dispositionReason() != null ? attempt.dispositionReason().wireName() : null,
				attempt.verdict() != null ? RecordedVerdict.from(attempt.verdict()) : null,
				attempt.failure() != null ? attempt.failure().code().wireName() : null);
	}

	public static RecordedCompositeAttempt legacy(int index, RecordedVerdict verdict) {
		return new RecordedCompositeAttempt("legacy-sub-verdict-" + index, "legacy_sub_verdict", null, verdict, null);
	}

	/** True when the parent could not use what this stage returned. */
	public boolean stageFailed() {
		return "stage_failed".equalsIgnoreCase(this.disposition) || "STAGE_FAILED".equals(this.disposition);
	}

}
