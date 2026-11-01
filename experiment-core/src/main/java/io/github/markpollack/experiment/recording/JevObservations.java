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
		var value = judgment.document().value(io.github.markpollack.judge.judgment.Judgment.class);
		var owners = io.github.markpollack.judge.verdict.InvocationRecords
			.of(io.github.markpollack.judge.verdict.Verdict.single("candidate", value));
		var usage = new java.util.ArrayList<UsageObservation>();
		var artifacts = new java.util.LinkedHashSet<>(observation.artifacts());
		for (var owner : owners) {
			artifacts.addAll(owner.artifacts());
			if (!"jev-systemone:0.2.0".equals(owner.protocol()))
				continue;
			JsonNode nativeUsage = ProducerDocument.parse(ProducerDocument.json(owner.nativeFacts())).path("usage");
			Long input = integer(nativeUsage.get("inputTokens"));
			Long output = integer(nativeUsage.get("outputTokens"));
			Double cost = null;
			String source = "jev-native-usage:v1:/usage";
			JsonNode charge = nativeUsage.path("cost");
			String declaredSource = nativeUsage.path("costSource").asText();
			if (charge.isNumber() && Double.isFinite(charge.doubleValue()) && charge.doubleValue() >= 0
					&& "USD".equals(nativeUsage.path("currency").asText())
					&& "vercel-gateway-reported:v1:/provider_metadata/gateway/cost".equals(declaredSource)) {
				cost = charge.doubleValue();
				source = declaredSource;
			}
			var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
				.enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
			var portable = ProducerDocument.parse(ProducerDocument.json(owner));
			String ownerHash;
			try {
				Object map = mapper.convertValue(portable, Object.class);
				ownerHash = ProducerDocument.hash(mapper.writeValueAsBytes(map));
			}
			catch (java.io.IOException failure) {
				throw new IllegalArgumentException("Cannot retain owner identity", failure);
			}
			usage.add(new UsageObservation(owner.id(), "typesafe.jev", source, "adapter-request", input, output,
					"input-plus-output:v1", cost, cost == null ? null : "USD", null, ownerHash));
		}
		return new InvocationObservation(observation.invocationId(), observation.captureMode(), observation.startedAt(),
				observation.endedAt(), observation.elapsedNanos(), observation.transportAttempts(),
				observation.requestId(), observation.transportElapsedNanos(), usage, List.copyOf(artifacts));
	}

	/**
	 * Attach a caller-selected retained transport trace after verifying its source
	 * reference and exact bytes.
	 */
	public static InvocationObservation withTransportTrace(InvocationObservation observation,
			io.github.markpollack.judge.provenance.ArtifactRef reference, byte[] bytes) {
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
