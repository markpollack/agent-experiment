package io.github.markpollack.experiment.store;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Groups variant results under a named session. One session corresponds to one invocation
 * of a multi-variant experiment run (e.g., {@code --run-all-variants}).
 *
 * @param sessionName human-readable session name (e.g., "full-suite-2026-03-03")
 * @param experimentName the experiment this session belongs to
 * @param createdAt when the session was created
 * @param completedAt when the session finished (null while running)
 * @param status current session status
 * @param variants per-variant entries, one per completed variant
 * @param metadata arbitrary key-value pairs (model, dataset version, etc.)
 */
public record RunSession(String sessionName, String experimentName, Instant createdAt, @Nullable Instant completedAt,
		RunSessionStatus status, List<VariantEntry> variants, Map<String, String> metadata) {

	public RunSession {
		variants = List.copyOf(variants);
		metadata = Map.copyOf(metadata);
	}

}
