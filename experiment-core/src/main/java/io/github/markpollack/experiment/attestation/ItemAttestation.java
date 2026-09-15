package io.github.markpollack.experiment.attestation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.markpollack.experiment.result.InstrumentRecord;
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

	/** Read an item's attestation with no roster to check it against. */
	public static ItemAttestation of(ItemResult item) {
		return of(item, null);
	}

	/**
	 * Read an item's attestation against the instrument its run recorded. Reads only;
	 * changes nothing.
	 *
	 * <p>
	 * The roster is used only when the verdict names that same instrument. A verdict
	 * scored by a different jury — an item a re-evaluation skipped keeps its original
	 * verdict — is never checked against a roster it was not scored by.
	 */
	public static ItemAttestation of(ItemResult item, @Nullable InstrumentRecord instrument) {
		RecordedVerdict verdict = item.verdict();
		if (verdict == null) {
			return new ItemAttestation(item.itemId(), item.itemSlug(), item.success(), null, Attestability.NOT_JUDGED,
					List.of());
		}
		List<VoteCount> votes = new ArrayList<>();
		collect(verdict, "jury", null, rosterFor(verdict, instrument), votes);
		return new ItemAttestation(item.itemId(), item.itemSlug(), item.success(), verdict.aggregated().status(),
				classify(votes), votes);
	}

	private static @Nullable Map<?, ?> rosterFor(RecordedVerdict verdict, @Nullable InstrumentRecord instrument) {
		if (instrument == null || instrument.description() == null || verdict.instrumentHash() == null) {
			return null;
		}
		return verdict.instrumentHash().equals(instrument.specHash()) ? instrument.description() : null;
	}

	private static Attestability classify(List<VoteCount> votes) {
		if (votes.stream().anyMatch(VoteCount::contradictsRoster)) {
			return Attestability.ROSTER_MISMATCH;
		}
		if (!votes.stream().allMatch(VoteCount::hasEvidence)) {
			return Attestability.NO_VOTE_EVIDENCE;
		}
		return votes.stream().allMatch(VoteCount::hasRoster) ? Attestability.ATTESTED
				: Attestability.VOTES_WITHOUT_ROSTER;
	}

	private static void collect(RecordedVerdict verdict, String scope, @Nullable String relation,
			@Nullable Map<?, ?> jury, List<VoteCount> into) {
		// A cascade's aggregate is a copy of its stopping tier's, and that tier is
		// already among the attempts; reading both would count it twice. Any other
		// aggregate is a reduction in its own right and is read as well.
		boolean cascade = verdict.compositeAttempts()
			.stream()
			.anyMatch(attempt -> CompositeRelation.CASCADE_TIER.wireName().equals(attempt.relation()));
		if (!cascade) {
			into.add(VoteCount.of(scope, relation, verdict.aggregated(), rosterSize(jury)));
		}
		for (RecordedCompositeAttempt attempt : verdict.compositeAttempts()) {
			Map<?, ?> child = child(jury, attempt.name());
			if (attempt.verdict() == null) {
				into.add(
						VoteCount.failed(attempt.name(), attempt.relation(), attempt.failureCode(), rosterSize(child)));
			}
			else {
				collect(attempt.verdict(), attempt.name(), attempt.relation(), child, into);
			}
		}
	}

	/**
	 * How many judgments a described jury submits to its own reduction: a simple jury's
	 * seats, or a meta-jury's members. A cascade reduces nothing itself — its tiers do —
	 * and an opaque jury lists no seats, so neither has a roster.
	 */
	private static @Nullable Integer rosterSize(@Nullable Map<?, ?> jury) {
		if (jury == null) {
			return null;
		}
		Object entries = switch (String.valueOf(jury.get("kind"))) {
			case "SIMPLE" -> jury.get("seats");
			case "META" -> jury.get("members");
			default -> null;
		};
		return entries instanceof List<?> list ? list.size() : null;
	}

	/** The described jury of a cascade tier or meta-jury member, matched by name. */
	private static @Nullable Map<?, ?> child(@Nullable Map<?, ?> jury, String name) {
		if (jury == null) {
			return null;
		}
		Object entries = switch (String.valueOf(jury.get("kind"))) {
			case "CASCADED" -> jury.get("tiers");
			case "META" -> jury.get("members");
			default -> null;
		};
		if (entries instanceof List<?> list) {
			for (Object entry : list) {
				if (entry instanceof Map<?, ?> described && name.equals(described.get("name"))
						&& described.get("jury") instanceof Map<?, ?> childJury) {
					return childJury;
				}
			}
		}
		return null;
	}

}
