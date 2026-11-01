package io.github.markpollack.experiment.recording;

import java.time.Instant;
import java.util.List;
import io.github.markpollack.judge.provenance.ArtifactRef;
import org.jspecify.annotations.Nullable;

/** Logical-call observation, separate from any observed transport attempt. */
public record InvocationObservation(String invocationId, String captureMode, @Nullable Instant startedAt,
		@Nullable Instant endedAt, @Nullable Long elapsedNanos, @Nullable Integer transportAttempts,
		@Nullable String requestId, @Nullable Long transportElapsedNanos, List<UsageObservation> usage,
		List<ArtifactRef> artifacts) {
	public InvocationObservation {
		if (invocationId == null || invocationId.isBlank()
				|| !List.of("FAKE", "FIXTURE", "RETAINED", "LIVE").contains(captureMode))
			throw new IllegalArgumentException("Invocation identity/capture mode required");
		if (elapsedNanos != null && elapsedNanos < 0 || transportElapsedNanos != null && transportElapsedNanos < 0
				|| transportAttempts != null && transportAttempts < 0)
			throw new IllegalArgumentException("Negative observation measurement");
		if (startedAt != null && endedAt != null && endedAt.isBefore(startedAt))
			throw new IllegalArgumentException("Reversed observation times");
		usage = List.copyOf(usage);
		artifacts = List.copyOf(artifacts);
	}

	public InvocationObservation withUsage(List<UsageObservation> values) {
		return new InvocationObservation(invocationId, captureMode, startedAt, endedAt, elapsedNanos, transportAttempts,
				requestId, transportElapsedNanos, values, artifacts);
	}
}
