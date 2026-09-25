package io.github.markpollack.experiment.recording;

import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.markpollack.experiment.result.RecordedJudgment;

/** Extracts the current Jev adapter's validated root usage once, never recursively. */
public final class JevObservations {

	private JevObservations() {
	}

	/**
	 * Preserve root usage only for the named adapter contract; trace lookup is
	 * caller-owned.
	 */
	public static InvocationObservation extract(RecordedJudgment judgment, InvocationObservation observation) {
		JsonNode source = judgment.document().node();
		JsonNode provenance = source.path("provenance");
		if (!"typesafe.jev".equals(provenance.path("instrumentId").asText())
				|| !provenance.path("revision").asText().startsWith("jev-adapter:2;jev-java:0.2.0;"))
			return observation;
		var references = new java.util.ArrayList<io.github.markpollack.judge.result.ArtifactRef>();
		for (JsonNode ref : provenance.path("evidence"))
			references.add(new io.github.markpollack.judge.result.ArtifactRef(ref.path("id").asText(),
					ref.path("sha256").asText(), ref.hasNonNull("selector") ? ref.path("selector").asText() : null));
		if (provenance.hasNonNull("response")) {
			JsonNode ref = provenance.path("response");
			references.add(new io.github.markpollack.judge.result.ArtifactRef(ref.path("id").asText(),
					ref.path("sha256").asText(), ref.hasNonNull("selector") ? ref.path("selector").asText() : null));
		}
		observation = new InvocationObservation(observation.invocationId(), observation.captureMode(),
				observation.startedAt(), observation.endedAt(), observation.elapsedNanos(),
				observation.transportAttempts(), observation.requestId(), observation.transportElapsedNanos(),
				observation.usage(), references);
		JsonNode usage = source.path("metadata").path("usage");
		Long input = integer(usage.get("inputTokens")), output = integer(usage.get("outputTokens"));
		if (input == null && output == null)
			return observation;
		UsageObservation value = new UsageObservation(observation.invocationId(), "typesafe.jev",
				"jev-root-usage:v1:" + provenance.path("revision").asText() + ":/metadata/usage", "adapter-request",
				input, output, "input-plus-output:v1", null, null, null);
		return observation.withUsage(List.of(value));
	}

	/**
	 * Attach a caller-selected retained transport trace after verifying its source
	 * reference and exact bytes.
	 */
	public static InvocationObservation withTransportTrace(InvocationObservation observation,
			io.github.markpollack.judge.result.ArtifactRef reference, byte[] bytes) {
		if (!observation.artifacts().contains(reference) || !ProducerDocument.hash(bytes).equals(reference.sha256()))
			throw new IllegalArgumentException("Trace reference/hash mismatch");
		JsonNode trace = ProducerDocument.parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
		Long attempts = integer(trace.get("attempts")), elapsed = integer(trace.get("elapsedNanos"));
		if (attempts == null || attempts > Integer.MAX_VALUE || elapsed == null)
			throw new IllegalArgumentException("Malformed transport trace");
		JsonNode request = trace.get("requestId");
		if (request != null && !request.isNull() && !request.isTextual())
			throw new IllegalArgumentException("Invalid request ID");
		return new InvocationObservation(observation.invocationId(), observation.captureMode(), observation.startedAt(),
				observation.endedAt(), observation.elapsedNanos(), attempts.intValue(),
				request == null || request.isNull() ? null : request.asText(), elapsed, observation.usage(),
				observation.artifacts());
	}

	private static Long integer(JsonNode node) {
		if (node == null || node.isNull())
			return null;
		if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 0)
			throw new IllegalArgumentException("Invalid Jev usage integer");
		return node.longValue();
	}

}
