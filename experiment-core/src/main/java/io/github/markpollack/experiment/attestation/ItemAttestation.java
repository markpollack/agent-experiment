package io.github.markpollack.experiment.attestation;

import java.util.ArrayList;
import java.util.List;

import io.github.markpollack.experiment.result.ItemResult;
import io.github.markpollack.experiment.result.RecordedCompositeAttempt;
import io.github.markpollack.experiment.result.RecordedJudgmentStatus;
import io.github.markpollack.experiment.result.RecordedVerdict;
import io.github.markpollack.judge.jury.CompositeRelation;
import org.jspecify.annotations.Nullable;

/**
 * How far one stored item's outcome can be traced to the judges that produced it.
 *
 * @param itemId stable item id
 * @param itemSlug human-readable item slug
 * @param invocationSucceeded whether the system under test completed
 * @param outcome the verdict's aggregated status, or null when the item was not judged
 * @param attestability weakest-link classification across {@code votes}
 * @param votes one entry per jury that contributed, in the order they were recorded
 */
public record ItemAttestation(String itemId, String itemSlug, boolean invocationSucceeded,
		@Nullable RecordedJudgmentStatus outcome, Attestability attestability, List<VoteCount> votes) {

	public ItemAttestation {
		java.util.Objects.requireNonNull(itemId, "itemId must not be null");
		java.util.Objects.requireNonNull(attestability, "attestability must not be null");
		votes = List.copyOf(votes);
	}

	/** Read an item's attestation from what it stored. Reads only; changes nothing. */
	public static ItemAttestation of(ItemResult item) {
		RecordedVerdict verdict = item.verdict();
		if (verdict == null) {
			return new ItemAttestation(item.itemId(), item.itemSlug(), item.success(), null, Attestability.NOT_JUDGED,
					List.of());
		}
		List<VoteCount> votes = new ArrayList<>();
		collect(verdict, "jury", null, votes);
		Attestability attestability = votes.stream().allMatch(VoteCount::hasEvidence)
				? Attestability.VOTES_WITHOUT_ROSTER : Attestability.NO_VOTE_EVIDENCE;
		return new ItemAttestation(item.itemId(), item.itemSlug(), item.success(), verdict.aggregated().status(),
				attestability, votes);
	}

	private static void collect(RecordedVerdict verdict, String scope, @Nullable String relation,
			List<VoteCount> into) {
		// A cascade's aggregate is a copy of its stopping tier's, and that tier is
		// already among the attempts; reading both would count it twice. Any other
		// aggregate is a reduction in its own right and is read as well.
		boolean cascade = verdict.compositeAttempts()
			.stream()
			.anyMatch(attempt -> CompositeRelation.CASCADE_TIER.wireName().equals(attempt.relation()));
		if (!cascade) {
			into.add(VoteCount.of(scope, relation, verdict.aggregated()));
		}
		for (RecordedCompositeAttempt attempt : verdict.compositeAttempts()) {
			if (attempt.verdict() == null) {
				into.add(VoteCount.failed(attempt.name(), attempt.relation(), attempt.failureCode()));
			}
			else {
				collect(attempt.verdict(), attempt.name(), attempt.relation(), into);
			}
		}
	}

}
