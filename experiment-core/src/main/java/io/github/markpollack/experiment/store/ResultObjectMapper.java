package io.github.markpollack.experiment.store;

import java.io.IOException;
import java.nio.file.Path;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springaicommunity.judge.score.BooleanScore;
import org.springaicommunity.judge.score.CategoricalScore;
import org.springaicommunity.judge.score.NumericalScore;
import org.springaicommunity.judge.score.Score;

/**
 * Creates a pre-configured {@link ObjectMapper} for serializing experiment results.
 * Handles Score sealed interface hierarchy via deduction, Path serialization, and
 * JavaTime types.
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

		// Score sealed interface — deduction by unique property presence
		mapper.addMixIn(Score.class, ScoreMixin.class);

		// Path and Throwable custom serialization
		SimpleModule module = new SimpleModule("ResultStoreModule");
		module.addSerializer(Path.class, new PathSerializer());
		module.addDeserializer(Path.class, new PathDeserializer());
		module.addSerializer(Throwable.class, new ThrowableSerializer());
		mapper.registerModule(module);

		return mapper;
	}

	// BooleanScore has only "value", shared by all subtypes — no unique property for
	// deduction. defaultImpl falls back to BooleanScore when Jackson can't discriminate.
	// NumericalScore identified by "min"/"max", CategoricalScore by "allowedValues".
	@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION, defaultImpl = BooleanScore.class)
	@JsonSubTypes({ @JsonSubTypes.Type(BooleanScore.class), @JsonSubTypes.Type(NumericalScore.class),
			@JsonSubTypes.Type(CategoricalScore.class) })
	interface ScoreMixin {

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

}
