package io.github.markpollack.experiment.result;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

/**
 * The instrument a stored result was scored with: the jury as it was configured,
 * described before any vote, and the library versions that described it.
 *
 * <p>
 * Each verdict's aggregation evidence says how many judges voted. Only this record says
 * how many should have. A jury that silently votes with fewer judges than it lists
 * produces a plausible verdict, so the difference is visible only when both are stored.
 *
 * <p>
 * Every component except {@code libraries} is nullable, and null means <em>not
 * captured</em>. A result stored before this record existed has no instrument at all. A
 * jury that could not be described carries {@code describeFailure} and nothing else,
 * rather than an empty description that would pass for a jury with no judges.
 *
 * <p>
 * {@code specHash} is the instrument's primary coordinate, but two hashes are comparable
 * only when their {@code descriptionVersion} is equal. A different version is a change of
 * description format, not of jury. An equal version with a different hash means the
 * instrument's description changed — which may be the consumer changing its jury, or a
 * library upgrade changing what a judge declares; {@code libraries} tells them apart.
 *
 * @param descriptionVersion description format version, read from the description
 * @param specHash SHA-256 of the description's canonical JSON
 * @param description the jury's portable description, stored whole
 * @param describeFailure why the jury could not be described, when it could not
 * @param libraries version per library, for each library whose version could be resolved;
 * a library absent from the map was not resolvable
 */
public record InstrumentRecord(@Nullable Integer descriptionVersion, @Nullable String specHash,
		@Nullable Map<String, Object> description, @Nullable String describeFailure, Map<String, String> libraries) {

	public InstrumentRecord {
		description = description == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(description));
		libraries = libraries == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(libraries));
	}

}
