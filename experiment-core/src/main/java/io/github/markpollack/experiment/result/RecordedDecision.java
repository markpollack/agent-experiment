package io.github.markpollack.experiment.result;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.markpollack.judge.jury.Decision;
import org.jspecify.annotations.Nullable;

/**
 * What produced a verdict's aggregate.
 *
 * <p>
 * This is how an item's outcome is read. A cascade that stopped at a tier records
 * {@code TIER} with the tier's name, and a {@code basis} saying whether the tier's own
 * outcome decided it or a single judge's rejection did. Reading the outcome from the
 * decision is the point: an aggregate that is ERROR because its reduction failed can
 * still sit on top of a real rejection, so <em>interpreting the aggregate's shape</em>
 * would lose the rejection or invent one.
 *
 * @param kind OWN, TIER or UNDECIDED, as a wire name
 * @param tier the deciding tier's name when kind is TIER, else null
 * @param basis TIER_OUTCOME or INDIVIDUAL_REJECTION when kind is TIER, else null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecordedDecision(String kind, @Nullable String tier, @Nullable String basis) {

	public RecordedDecision {
		java.util.Objects.requireNonNull(kind, "kind must not be null");
	}

	public static @Nullable RecordedDecision from(@Nullable Decision decision) {
		if (decision == null) {
			return null;
		}
		return new RecordedDecision(decision.kind().wireName(), decision.tier(),
				decision.basis() != null ? decision.basis().wireName() : null);
	}

}
