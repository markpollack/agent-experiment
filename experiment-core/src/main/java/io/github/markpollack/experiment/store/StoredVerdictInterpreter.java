package io.github.markpollack.experiment.store;

import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.Nullable;

/**
 * Reads only current V6 through the producer codec; older sources remain raw and
 * unsupported.
 */
final class StoredVerdictInterpreter implements InterpretationReExport.VerdictInterpreter {

	private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
	};

	private final ObjectMapper mapper;

	StoredVerdictInterpreter(ObjectMapper mapper) {
		this.mapper = mapper;
	}

	@Override
	public @Nullable ObjectNode interpret(JsonNode storedVerdict) {
		try {
			var source = io.github.markpollack.experiment.recording.ProducerDocument.text("VERDICT",
					storedVerdict.toString());
			return (ObjectNode) io.github.markpollack.experiment.recording.InterpretationSnapshot
				.currentReading(source);
		}
		catch (IllegalArgumentException unsupported) {
			return null;
		}
	}

}
