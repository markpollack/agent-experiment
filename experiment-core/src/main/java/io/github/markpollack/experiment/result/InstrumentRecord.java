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
 * A version string is not an identity when it is a snapshot: {@code 0.17.0-SNAPSHOT}
 * resolves to a different build every time the library's main branch moves, and the jar's
 * own Maven metadata carries only that floating string. {@code artifacts} therefore
 * records the SHA-256 of the jar each library was actually loaded from, which identifies
 * the exact code that scored the run.
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
 * @param artifacts SHA-256 of the jar each library was actually loaded from, keyed as
 * {@code libraries} is
 */
public record InstrumentRecord(@Nullable Integer descriptionVersion, @Nullable String specHash,
		@Nullable Map<String, Object> description, @Nullable String describeFailure, Map<String, String> libraries,
		Map<String, String> artifacts) {

	/** Item metadata key marking an item whose jury did not convene as configured. */
	public static final String ITEM_INSTRUMENT_FAILURE = "instrumentFailure";

	/** Item metadata key carrying what the instrument failure was. */
	public static final String ITEM_INSTRUMENT_FAILURE_DETAIL = "instrumentFailureDetail";

	public InstrumentRecord {
		description = description == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(description));
		libraries = libraries == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(libraries));
		artifacts = artifacts == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(artifacts));
	}

}
