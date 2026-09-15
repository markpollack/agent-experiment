package io.github.markpollack.experiment.attestation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.github.markpollack.experiment.result.ExperimentResult;
import io.github.markpollack.experiment.result.InstrumentRecord;
import io.github.markpollack.experiment.result.ItemResult;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Marks and raises the case where a jury did not convene as its configuration says it
 * should.
 *
 * <p>
 * The comparison is the roster against the judgments the jury <em>submitted</em>, never
 * against the judgments that produced a usable result. A judge that throws, returns
 * nothing, or carries unreadable metadata still occupies its seat and still votes, as an
 * ERROR judgment: it is accounted for, not missing. Counting only the judges that
 * succeeded would fire this on every run with one broken judge, which is a different
 * fault with a different remedy.
 *
 * <p>
 * The mark says the <em>jury</em> failed, never that the agent did. An instrument failure
 * scored against the subject is the defect this whole record exists to make visible.
 */
public final class InstrumentFailures {

	private static final Logger logger = LoggerFactory.getLogger(InstrumentFailures.class);

	private InstrumentFailures() {
	}

	/**
	 * Mark an item whose jury voted with a different number of judges than it lists.
	 * @param item the scored item
	 * @param instrument the instrument that scored it, or null when none was recorded
	 * @return the item, marked when its jury did not convene as configured
	 */
	public static ItemResult mark(ItemResult item, @Nullable InstrumentRecord instrument) {
		ItemAttestation attestation = ItemAttestation.of(item, instrument);
		if (attestation.attestability() != Attestability.ROSTER_MISMATCH) {
			return item;
		}
		String detail = attestation.votes()
			.stream()
			.filter(VoteCount::contradictsRoster)
			.map(vote -> vote.scope() + ": lists " + vote.rosterCount() + ", voted " + vote.inputCount())
			.collect(Collectors.joining("; "));
		logger.error("Item {}: the jury did not convene as configured — {}. The result is kept and the run will fail.",
				item.itemId(), detail);
		Map<String, Object> metadata = new LinkedHashMap<>(item.metadata());
		metadata.put(InstrumentRecord.ITEM_INSTRUMENT_FAILURE, "rosterMismatch");
		metadata.put(InstrumentRecord.ITEM_INSTRUMENT_FAILURE_DETAIL, detail);
		return item.toBuilder().metadata(metadata).build();
	}

	/**
	 * Fail the run when any item's jury did not convene as configured. Call this only
	 * after the result has been persisted.
	 * @param result the saved result
	 * @throws InstrumentFailureException when any item is marked
	 */
	public static void failIfAny(ExperimentResult result) {
		List<String> failed = result.items()
			.stream()
			.filter(item -> item.metadata().containsKey(InstrumentRecord.ITEM_INSTRUMENT_FAILURE))
			.map(ItemResult::itemId)
			.toList();
		if (failed.isEmpty()) {
			return;
		}
		throw new InstrumentFailureException("The jury did not convene as configured for " + failed.size() + " of "
				+ result.items().size() + " item(s): " + String.join(", ", failed) + ". Result " + result.experimentId()
				+ " is saved — re-judge it with ReEvaluator rather than re-running the agent.", failed);
	}

}
