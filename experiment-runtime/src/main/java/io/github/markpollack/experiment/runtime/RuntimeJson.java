package io.github.markpollack.experiment.runtime;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.markpollack.judge.serialization.ResultJson;

final class RuntimeJson {

	private RuntimeJson() {
	}

	static ObjectMapper mapper() {
		ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
			.registerModule(ResultJson.module());
		mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
		mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
		mapper.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
		mapper.enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);
		mapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
		mapper.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
		return mapper;
	}

}
