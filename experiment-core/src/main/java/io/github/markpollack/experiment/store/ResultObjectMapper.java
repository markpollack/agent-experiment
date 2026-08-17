package io.github.markpollack.experiment.store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.markpollack.experiment.agent.InvocationResult;
import io.github.markpollack.experiment.judge.JudgeExecutionDetail;
import io.github.markpollack.experiment.result.ExecutionDetail;
import io.github.markpollack.experiment.result.RecordedCheck;
import io.github.markpollack.experiment.result.RecordedCompositeAttempt;
import io.github.markpollack.experiment.result.RecordedJudgment;
import io.github.markpollack.experiment.result.RecordedJudgmentStatus;
import io.github.markpollack.experiment.result.RecordedVerdict;

/**
 * Creates a pre-configured {@link ObjectMapper} for serializing experiment results.
 * Handles the Agent Experiment-owned result wire model, legacy Agent Judge 0.13 result
 * migration, Path serialization, and JavaTime types.
 */
final class ResultObjectMapper {

	private ResultObjectMapper() {
	}

	/**
	 * Create an ObjectMapper configured for experiment result serialization.
	 * @return a configured ObjectMapper
	 */
	static ObjectMapper create() {
		ObjectMapper mapper = new ObjectMapper();

		// JavaTime support (Instant, Duration)
		mapper.registerModule(new JavaTimeModule());
		mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

		// Pretty print for human readability
		mapper.enable(SerializationFeature.INDENT_OUTPUT);

		// Ignore unknown properties for forward compatibility
		mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

		// ExecutionDetail polymorphic deserialization via deduction.
		// InvocationResult (agent) and JudgeExecutionDetail (judge) discriminated
		// by unique property presence.
		mapper.addMixIn(ExecutionDetail.class, ExecutionDetailMixin.class);

		// Path and Throwable custom serialization
		SimpleModule module = new SimpleModule("ResultStoreModule");
		module.addSerializer(Path.class, new PathSerializer());
		module.addDeserializer(Path.class, new PathDeserializer());
		module.addSerializer(Throwable.class, new ThrowableSerializer());
		module.addDeserializer(RecordedJudgment.class, new RecordedJudgmentDeserializer());
		module.addDeserializer(RecordedVerdict.class, new RecordedVerdictDeserializer());
		mapper.registerModule(module);

		return mapper;
	}

	// ExecutionDetail — deduction by unique property presence.
	// InvocationResult has "status"/"phases", JudgeExecutionDetail has
	// "candidateJudgment"/"expectedLabel"/"scorerResult".
	@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION, defaultImpl = InvocationResult.class)
	@JsonSubTypes({ @JsonSubTypes.Type(InvocationResult.class), @JsonSubTypes.Type(JudgeExecutionDetail.class) })
	interface ExecutionDetailMixin {

	}

	static final class PathSerializer extends StdSerializer<Path> {

		PathSerializer() {
			super(Path.class);
		}

		@Override
		public void serialize(Path value, JsonGenerator gen, SerializerProvider provider) throws IOException {
			gen.writeString(value.toString());
		}

	}

	static final class PathDeserializer extends StdDeserializer<Path> {

		PathDeserializer() {
			super(Path.class);
		}

		@Override
		public Path deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
			return Path.of(p.getValueAsString());
		}

	}

	static final class ThrowableSerializer extends StdSerializer<Throwable> {

		ThrowableSerializer() {
			super(Throwable.class);
		}

		@Override
		public void serialize(Throwable value, JsonGenerator gen, SerializerProvider provider) throws IOException {
			gen.writeStartObject();
			gen.writeStringField("className", value.getClass().getName());
			gen.writeStringField("message", value.getMessage());
			gen.writeEndObject();
		}

	}

	/**
	 * Reads both the normalized 0.14 projection and the former polymorphic score shape.
	 */
	static final class RecordedJudgmentDeserializer extends StdDeserializer<RecordedJudgment> {

		private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
		};

		RecordedJudgmentDeserializer() {
			super(RecordedJudgment.class);
		}

		@Override
		public RecordedJudgment deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
			ObjectMapper mapper = (ObjectMapper) p.getCodec();
			JsonNode node = mapper.readTree(p);
			RecordedJudgmentStatus status = RecordedJudgmentStatus.fromWire(requiredText(node, "status"));
			String reasoning = node.path("reasoning").asText("");
			String label = nullableText(node.get("label"));
			Double score = normalizedScore(node.get("score"));

			JsonNode legacyScore = node.get("score");
			if (label == null && legacyScore != null && legacyScore.isObject()
					&& legacyScore.path("value").isTextual()) {
				label = legacyScore.path("value").asText();
			}

			List<RecordedCheck> checks = new ArrayList<>();
			for (JsonNode check : node.path("checks")) {
				checks.add(new RecordedCheck(requiredText(check, "name"), check.path("passed").asBoolean(),
						check.path("message").asText("")));
			}
			Map<String, Object> metadata = node.hasNonNull("metadata")
					? mapper.convertValue(node.get("metadata"), OBJECT_MAP) : Map.of();
			return new RecordedJudgment(status, score, label, reasoning, checks, metadata);
		}

		private static Double normalizedScore(JsonNode score) throws IOException {
			if (score == null || score.isNull()) {
				return null;
			}
			if (score.isNumber()) {
				return score.doubleValue();
			}
			if (!score.isObject()) {
				throw new IOException("Unsupported recorded judgment score shape: " + score);
			}

			JsonNode value = score.get("value");
			if (value == null || value.isBoolean() || value.isTextual()) {
				// Boolean outcomes move to status; categorical values move to label.
				return null;
			}
			if (value.isNumber() && score.path("min").isNumber() && score.path("max").isNumber()) {
				double minimum = score.path("min").doubleValue();
				double maximum = score.path("max").doubleValue();
				if (maximum <= minimum) {
					throw new IOException("Legacy numerical score has a non-positive range");
				}
				return (value.doubleValue() - minimum) / (maximum - minimum);
			}
			throw new IOException("Unsupported legacy score object: " + score);
		}

	}

	/** Reads Judge 0.14-style attempt trees and legacy 0.13 sub-verdict trees. */
	static final class RecordedVerdictDeserializer extends StdDeserializer<RecordedVerdict> {

		RecordedVerdictDeserializer() {
			super(RecordedVerdict.class);
		}

		@Override
		public RecordedVerdict deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
			ObjectMapper mapper = (ObjectMapper) p.getCodec();
			JsonNode node = mapper.readTree(p);
			RecordedJudgment aggregated = mapper.treeToValue(node.get("aggregated"), RecordedJudgment.class);

			List<RecordedJudgment> individual = new ArrayList<>();
			for (JsonNode judgment : node.path("individual")) {
				individual.add(mapper.treeToValue(judgment, RecordedJudgment.class));
			}

			Map<String, RecordedJudgment> individualByName = new LinkedHashMap<>();
			for (var entry : node.path("individualByName").properties()) {
				individualByName.put(entry.getKey(), mapper.treeToValue(entry.getValue(), RecordedJudgment.class));
			}

			Map<String, Double> weights = new LinkedHashMap<>();
			node.path("weights")
				.properties()
				.forEach(entry -> weights.put(entry.getKey(), entry.getValue().asDouble()));

			List<RecordedCompositeAttempt> attempts = new ArrayList<>();
			for (JsonNode attempt : node.path("compositeAttempts")) {
				attempts.add(mapper.treeToValue(attempt, RecordedCompositeAttempt.class));
			}
			int legacyIndex = 0;
			for (JsonNode subVerdict : node.path("subVerdicts")) {
				attempts.add(RecordedCompositeAttempt.legacy(legacyIndex++,
						mapper.treeToValue(subVerdict, RecordedVerdict.class)));
			}
			return new RecordedVerdict(aggregated, individual, individualByName, weights, attempts);
		}

	}

	private static String requiredText(JsonNode node, String field) throws IOException {
		JsonNode value = node.get(field);
		if (value == null || !value.isTextual()) {
			throw new IOException("Required text field is missing: " + field);
		}
		return value.asText();
	}

	private static String nullableText(JsonNode value) {
		return value == null || value.isNull() ? null : value.asText();
	}

}
