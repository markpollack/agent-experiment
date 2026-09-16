package io.github.markpollack.experiment.result;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.markpollack.judge.jury.Seat;
import org.jspecify.annotations.Nullable;

/**
 * Where one judgment sat in the jury that produced a verdict, and under what key.
 *
 * <p>
 * The seat is where a verdict joins its jury's configuration: {@code position} is the
 * index into the verdict's individual judgments, and {@code verdictKey} is the key those
 * judgments are stored under by name.
 *
 * <p>
 * {@code keySource} says whether that key is an identity or an accident of order.
 * {@code POSITIONAL} means the judge declared no name and its key is its slot, so
 * inserting a judge above it moves the key. Such a key is a join key for one
 * configuration and must never be used as a judge's identity across runs.
 *
 * @param position index of the judgment this seat holds
 * @param verdictKey key the judgment is stored under
 * @param keySource DECLARED, DEDUPLICATED or POSITIONAL, or null when not recorded
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecordedSeat(int position, String verdictKey, @Nullable String keySource) {

	public RecordedSeat {
		java.util.Objects.requireNonNull(verdictKey, "verdictKey must not be null");
	}

	public static RecordedSeat from(Seat seat) {
		return new RecordedSeat(seat.position(), seat.verdictKey(),
				seat.keySource() != null ? seat.keySource().wireName() : null);
	}

}
