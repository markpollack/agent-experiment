package io.github.markpollack.experiment.result.history;

import java.util.ArrayList;
import java.util.List;

import io.github.markpollack.experiment.result.RecordedSeat;
import org.jspecify.annotations.Nullable;

/**
 * A seat as it was stored, including the parts it did not store.
 *
 * <p>
 * A position is a fact, not a default. A stored seat missing its position is not seat
 * zero, and a seat missing its key is not the empty key: both are unrecorded, and a
 * conversion that invented them would attach judgments to slots nobody wrote down.
 *
 * @param position index of the judgment this seat holds, or null when unrecorded
 * @param verdictKey key the judgment is stored under, or null when unrecorded
 * @param keySource whether that key is an identity, when recorded
 */
public record HistoricalSeat(@Nullable Integer position, @Nullable String verdictKey, @Nullable String keySource) {

	/** Required facts this seat did not record. */
	public List<String> unrecorded(String path) {
		List<String> missing = new ArrayList<>();
		if (this.position == null) {
			missing.add(path + ".position");
		}
		if (this.verdictKey == null) {
			missing.add(path + ".verdictKey");
		}
		return missing;
	}

	RecordedSeat toLive() {
		List<String> missing = unrecorded("seat");
		if (!missing.isEmpty()) {
			throw new IllegalStateException("cannot convert a seat with unrecorded required facts: " + missing);
		}
		return new RecordedSeat(this.position, this.verdictKey, this.keySource);
	}

}
