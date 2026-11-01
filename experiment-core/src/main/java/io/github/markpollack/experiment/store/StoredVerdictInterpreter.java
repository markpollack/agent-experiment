package io.github.markpollack.experiment.store;

import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.Nullable;

/**
 * Reads a stored verdict of any age by asking the library that produced it.
 *
 * <p>
 * The whole of this class is handing a parsed map over and serialising what comes back.
 * That is the point: what a verdict says — which stage decided, what it says about the
 * subject, what the record is missing — is the library's to answer, and nothing here
 * inspects the verdict or second-guesses the answer.
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
