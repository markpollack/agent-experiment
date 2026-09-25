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
import io.github.markpollack.experiment.result.RecordedDecision;
import io.github.markpollack.experiment.result.RecordedJudgment;
import io.github.markpollack.experiment.result.RecordedJudgmentStatus;
import io.github.markpollack.experiment.result.RecordedSeat;
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
		mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
		mapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
		mapper.disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS);

		// ExecutionDetail polymorphic deserialization via deduction.
		// InvocationResult (agent) and JudgeExecutionDetail (judge) discriminated
		// by unique property presence.
		// Current details use an explicit kind; old documents use the bounded legacy
		// dispatch below.

		// Path and Throwable custom serialization
		SimpleModule module = new SimpleModule("ResultStoreModule");
		module.addSerializer(Path.class, new PathSerializer());
		module.addDeserializer(Path.class, new PathDeserializer());
		module.addSerializer(Throwable.class, new ThrowableSerializer());
		module.addDeserializer(RecordedJudgment.class, new RecordedJudgmentDeserializer());
		module.addDeserializer(RecordedVerdict.class, new RecordedVerdictDeserializer());
		module.addDeserializer(ExecutionDetail.class, new DetailDeserializer());
		module.addSerializer(io.github.markpollack.experiment.result.ItemResult.class, new ItemSerializer());
		module.addDeserializer(io.github.markpollack.experiment.result.ItemResult.class, new ItemDeserializer());
		module.setDeserializerModifier(new com.fasterxml.jackson.databind.deser.BeanDeserializerModifier() {
			@Override
			public com.fasterxml.jackson.databind.JsonDeserializer<?> modifyDeserializer(
					com.fasterxml.jackson.databind.DeserializationConfig config,
					com.fasterxml.jackson.databind.BeanDescription bean,
					com.fasterxml.jackson.databind.JsonDeserializer<?> deserializer) {
				return bean.getBeanClass() == io.github.markpollack.experiment.result.ExperimentResult.class
						? new EnvelopeDeserializer(deserializer) : deserializer;
			}
		});
		module.setSerializerModifier(new com.fasterxml.jackson.databind.ser.BeanSerializerModifier() {
			@Override
			public com.fasterxml.jackson.databind.JsonSerializer<?> modifySerializer(
					com.fasterxml.jackson.databind.SerializationConfig config,
					com.fasterxml.jackson.databind.BeanDescription bean,
					com.fasterxml.jackson.databind.JsonSerializer<?> serializer) {
				return bean.getBeanClass() == io.github.markpollack.experiment.result.ExperimentResult.class
						? new EnvelopeSerializer(serializer) : serializer;
			}
		});

		mapper.registerModule(module);

		return mapper;
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

	static final class RecordedJudgmentDeserializer extends StdDeserializer<RecordedJudgment> {

		RecordedJudgmentDeserializer() {
			super(RecordedJudgment.class);
		}

		@Override
		public RecordedJudgment deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
			ObjectMapper mapper = (ObjectMapper) p.getCodec();
			JsonNode node = mapper.readTree(p);
			return node.has("sourceKind")
					? RecordedJudgment.fromDocument(
							mapper.treeToValue(node, io.github.markpollack.experiment.recording.ProducerDocument.class))
					: RecordedJudgment.fromProducerJson(node.toString());
		}

	}

	static final class RecordedVerdictDeserializer extends StdDeserializer<RecordedVerdict> {

		RecordedVerdictDeserializer() {
			super(RecordedVerdict.class);
		}

		@Override
		public RecordedVerdict deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
			ObjectMapper mapper = (ObjectMapper) p.getCodec();
			JsonNode node = mapper.readTree(p);
			return node.has("sourceKind")
					? RecordedVerdict.fromDocument(
							mapper.treeToValue(node, io.github.markpollack.experiment.recording.ProducerDocument.class))
					: RecordedVerdict.fromProducerJson(node.toString());
		}

	}

	static final class DetailDeserializer extends StdDeserializer<ExecutionDetail> {

		DetailDeserializer() {
			super(ExecutionDetail.class);
		}

		@Override
		public ExecutionDetail deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
			ObjectMapper mapper = (ObjectMapper) p.getCodec();
			JsonNode node = mapper.readTree(p);
			if (node.has("kind")) {
				String kind = node.path("kind").asText();
				if (!node.path("schemaVersion").isInt() || node.path("schemaVersion").intValue() != 1)
					throw new IOException("Unsupported execution detail version");
				return switch (kind) {
					case "JUDGE_EVALUATION" -> mapper.treeToValue(node, JudgeExecutionDetail.class);
					case "AGENT" -> mapper.treeToValue(node, InvocationResult.class);
					default -> throw new IOException("Unsupported execution detail kind: " + kind);
				};
			}
			if (node.has("candidateJudgment") || node.has("expectedLabel") || node.has("scorerResult")) {
				if (node.has("status") || node.has("phases"))
					throw new IOException("Ambiguous historical execution detail");
				return new JudgeExecutionDetail(
						mapper.treeToValue(node.get("candidateJudgment"), RecordedJudgment.class),
						nullableText(node.get("expectedLabel")), mapper.treeToValue(node.get("scorerResult"),
								io.github.markpollack.experiment.judge.JudgeScorerResult.class));
			}
			return mapper.treeToValue(node, InvocationResult.class);
		}

	}

	static final class ItemSerializer extends StdSerializer<io.github.markpollack.experiment.result.ItemResult> {

		ItemSerializer() {
			super(io.github.markpollack.experiment.result.ItemResult.class);
		}

		@Override
		public void serialize(io.github.markpollack.experiment.result.ItemResult value, JsonGenerator gen,
				SerializerProvider provider) throws IOException {
			gen.writeStartObject();
			gen.writeStringField("itemId", value.itemId());
			gen.writeStringField("itemSlug", value.itemSlug());
			gen.writeBooleanField("success", value.success());
			gen.writeObjectField("passed", value.passed());
			gen.writeObjectField("costUsd", value.costUsd());
			gen.writeObjectField("totalTokens", value.totalTokens());
			gen.writeObjectField("durationMs", value.durationMs());
			gen.writeObjectField("scores", value.scores());
			gen.writeObjectField("metrics", value.metrics());
			if (value.executionDetail() instanceof InvocationResult agent) {
				ObjectMapper mapper = (ObjectMapper) gen.getCodec();
				var detail = mapper.valueToTree(agent);
				((com.fasterxml.jackson.databind.node.ObjectNode) detail).put("kind", "AGENT").put("schemaVersion", 1);
				gen.writeObjectField("executionDetail", detail);
			}
			else if (value.executionDetail() instanceof JudgeExecutionDetail legacy
					&& "LEGACY_JUDGE".equals(legacy.kind())) {
				var detail = ((ObjectMapper) gen.getCodec()).createObjectNode();
				detail.set("candidateJudgment",
						((ObjectMapper) gen.getCodec()).valueToTree(legacy.candidateJudgment()));
				detail.put("expectedLabel", legacy.expectedLabel());
				detail.set("scorerResult", ((ObjectMapper) gen.getCodec()).valueToTree(legacy.scorerResult()));
				gen.writeObjectField("executionDetail", detail);
			}
			else
				gen.writeObjectField("executionDetail", value.executionDetail());
			gen.writeObjectField("verdict", value.verdict());
			gen.writeObjectField("verdictRole", value.verdictRole());
			gen.writeObjectField("interpretation",
					value.interpretationSnapshot() != null ? value.interpretationSnapshot() : value.interpretation());
			gen.writeObjectField("metadata", value.metadata());
			gen.writeObjectField("workspacePath", value.workspacePath());
			gen.writeEndObject();
		}

	}

	static final class ItemDeserializer extends StdDeserializer<io.github.markpollack.experiment.result.ItemResult> {

		ItemDeserializer() {
			super(io.github.markpollack.experiment.result.ItemResult.class);
		}

		@Override
		public io.github.markpollack.experiment.result.ItemResult deserialize(JsonParser p, DeserializationContext ctxt)
				throws IOException {
			ObjectMapper mapper = (ObjectMapper) p.getCodec();
			JsonNode n = mapper.readTree(p);
			var builder = io.github.markpollack.experiment.result.ItemResult.builder()
				.itemId(requiredText(n, "itemId"))
				.itemSlug(requiredText(n, "itemSlug"))
				.success(n.path("success").asBoolean(false))
				.passed(n.path("passed").isBoolean() ? n.path("passed").booleanValue() : null)
				.costUsd(n.hasNonNull("costUsd") ? n.get("costUsd").doubleValue() : null)
				.totalTokens(n.hasNonNull("totalTokens") ? n.get("totalTokens").longValue() : null)
				.durationMs(n.hasNonNull("durationMs") ? n.get("durationMs").longValue() : null)
				.scores(n.hasNonNull("scores")
						? mapper.convertValue(n.get("scores"), new TypeReference<Map<String, Double>>() {
						}) : Map.of())
				.metrics(n.hasNonNull("metrics")
						? mapper.convertValue(n.get("metrics"), new TypeReference<Map<String, Object>>() {
						}) : Map.of())
				.metadata(n.hasNonNull("metadata")
						? mapper.convertValue(n.get("metadata"), new TypeReference<Map<String, Object>>() {
						}) : Map.of())
				.workspacePath(n.hasNonNull("workspacePath") ? Path.of(n.get("workspacePath").asText()) : null)
				.executionDetail(n.hasNonNull("executionDetail")
						? mapper.treeToValue(n.get("executionDetail"), ExecutionDetail.class) : null)
				.verdictRole(nullableText(n.get("verdictRole")));
			RecordedVerdict verdict = n.hasNonNull("verdict")
					? mapper.treeToValue(n.get("verdict"), RecordedVerdict.class) : null;
			builder.verdict(verdict);
			JsonNode reading = n.get("interpretation");
			if (reading != null && reading.has("state")) {
				var snapshot = mapper.treeToValue(reading,
						io.github.markpollack.experiment.recording.InterpretationSnapshot.class);
				builder.interpretationSnapshot(snapshot)
					.interpretation(snapshot.reading())
					.historical(snapshot.state().equals("LEGACY_UNVERIFIED"));
			}
			else {
				boolean currentEnvelope = Boolean.TRUE.equals(ctxt.getAttribute("experiment.currentEnvelope"));
				if (currentEnvelope && verdict != null)
					throw new IOException("Current verdict requires a bound interpretation snapshot");
				builder.historical(!currentEnvelope);
				if (verdict != null) {
					var snapshot = new io.github.markpollack.experiment.recording.InterpretationSnapshot(
							"LEGACY_UNVERIFIED", verdict.sha256(), null, null,
							reading == null ? com.fasterxml.jackson.databind.node.NullNode.instance : reading);
					builder.interpretationSnapshot(snapshot).interpretation(snapshot.reading());
				}
			}
			return builder.build();
		}

	}

	static final class EnvelopeSerializer extends com.fasterxml.jackson.databind.JsonSerializer<Object>
			implements com.fasterxml.jackson.databind.ser.ResolvableSerializer {

		private final com.fasterxml.jackson.databind.JsonSerializer<Object> delegate;

		@SuppressWarnings("unchecked")
		EnvelopeSerializer(com.fasterxml.jackson.databind.JsonSerializer<?> delegate) {
			this.delegate = (com.fasterxml.jackson.databind.JsonSerializer<Object>) delegate;
		}

		@Override
		public void resolve(SerializerProvider provider) throws com.fasterxml.jackson.databind.JsonMappingException {
			if (delegate instanceof com.fasterxml.jackson.databind.ser.ResolvableSerializer r)
				r.resolve(provider);
		}

		@Override
		public void serialize(Object value, JsonGenerator gen, SerializerProvider provider) throws IOException {
			io.github.markpollack.experiment.recording.RecordingValidation
				.validate((io.github.markpollack.experiment.result.ExperimentResult) value);
			delegate.serialize(value, gen, provider);
		}

	}

	static final class EnvelopeDeserializer extends com.fasterxml.jackson.databind.deser.std.DelegatingDeserializer {

		EnvelopeDeserializer(com.fasterxml.jackson.databind.JsonDeserializer<?> delegate) {
			super(delegate);
		}

		@Override
		protected com.fasterxml.jackson.databind.JsonDeserializer<?> newDelegatingInstance(
				com.fasterxml.jackson.databind.JsonDeserializer<?> delegate) {
			return new EnvelopeDeserializer(delegate);
		}

		@Override
		public Object deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
			JsonNode tree = p.getCodec().readTree(p);
			if (p.getParsingContext().inRoot() && p.nextToken() != null)
				throw new IOException("Trailing Experiment recording content");
			if (tree.has("recordingSchemaVersion")) {
				if (!tree.get("recordingSchemaVersion").isInt() || tree.get("recordingSchemaVersion").intValue() != 2)
					throw new IOException("Unsupported Experiment recording schema");
				for (String field : List.of("totalTokens", "totalDurationMs", "declaredRows", "attemptedRows"))
					if (tree.hasNonNull(field) && (!tree.get(field).isIntegralNumber()
							|| !tree.get(field).canConvertToLong() || tree.get(field).longValue() < 0))
						throw new IOException("Invalid integer measurement: " + field);
				for (JsonNode item : tree.path("items")) {
					JsonNode detail = item.path("executionDetail");
					if (!detail.isMissingNode() && !detail.isNull()
							&& (!detail.hasNonNull("kind") || !detail.hasNonNull("schemaVersion")))
						throw new IOException("Current execution detail needs kind/version");
					if (item.hasNonNull("verdict") && !item.path("verdict").has("sourceKind"))
						throw new IOException("Current verdict requires a source wrapper");
					for (String field : List.of("totalTokens", "durationMs"))
						if (item.hasNonNull(field) && (!item.get(field).isIntegralNumber()
								|| !item.get(field).canConvertToLong() || item.get(field).longValue() < 0))
							throw new IOException("Invalid integer measurement: " + field);
					if (item.hasNonNull("costUsd")
							&& (!item.get("costUsd").isNumber() || !Double.isFinite(item.get("costUsd").doubleValue())
									|| item.get("costUsd").doubleValue() < 0))
						throw new IOException("Invalid cost measurement");
				}
			}
			try (JsonParser input = tree.traverse(p.getCodec())) {
				input.nextToken();
				Object previous = ctxt.getAttribute("experiment.currentEnvelope");
				io.github.markpollack.experiment.result.ExperimentResult value;
				ctxt.setAttribute("experiment.currentEnvelope", tree.has("recordingSchemaVersion"));
				try {
					value = (io.github.markpollack.experiment.result.ExperimentResult) _delegatee.deserialize(input,
							ctxt);
				}
				finally {
					ctxt.setAttribute("experiment.currentEnvelope", previous);
				}
				io.github.markpollack.experiment.recording.RecordingValidation.validate(value);
				if ("JUDGE".equals(value.experimentKind()) && (!tree.path("declaredRows").isInt()
						|| tree.path("declaredRows").intValue() != value.declaredRows()
						|| !tree.path("attemptedRows").isInt()
						|| tree.path("attemptedRows").intValue() != value.attemptedRows()))
					throw new IOException("Declared/attempted population mismatch");
				return value;
			}
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
