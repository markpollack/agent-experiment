package io.github.markpollack.experiment.result;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;
import io.github.markpollack.judge.jury.CompositeAttempt;

/** Agent Experiment-owned persisted form of a Judge 0.14 composite jury attempt. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecordedCompositeAttempt(String name, String relation, @Nullable String policy,
		@Nullable RecordedVerdict verdict, @Nullable String failureCode) {

	public RecordedCompositeAttempt {
		java.util.Objects.requireNonNull(name, "name must not be null");
		java.util.Objects.requireNonNull(relation, "relation must not be null");
		if ((verdict == null) == (failureCode == null)) {
			throw new IllegalArgumentException("exactly one of verdict or failureCode must be present");
		}
	}

	public static RecordedCompositeAttempt from(CompositeAttempt attempt) {
		return new RecordedCompositeAttempt(attempt.name(), attempt.relation().wireName(),
				attempt.policy() != null ? attempt.policy().wireName() : null,
				attempt.verdict() != null ? RecordedVerdict.from(attempt.verdict()) : null,
				attempt.failure() != null ? attempt.failure().code().wireName() : null);
	}

	public static RecordedCompositeAttempt legacy(int index, RecordedVerdict verdict) {
		return new RecordedCompositeAttempt("legacy-sub-verdict-" + index, "legacy_sub_verdict", null, verdict, null);
	}

}
