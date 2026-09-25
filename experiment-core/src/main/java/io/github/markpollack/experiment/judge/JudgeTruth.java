package io.github.markpollack.experiment.judge;

import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.markpollack.experiment.recording.ProducerDocument;
import org.jspecify.annotations.Nullable;

/** The actual supplied comparison truth, with optional caller provenance and domain. */
public record JudgeTruth(String kind, JsonNode value, @Nullable String sourceId, @Nullable String revision,
		@Nullable String scaleId, @Nullable Double minimum, @Nullable Double maximum, List<String> alternatives) {
	public JudgeTruth {
		if (!List.of("STATUS_LABEL", "PROPOSITION", "CATEGORY", "NUMERIC").contains(kind))
			throw new IllegalArgumentException("Unknown truth kind");
		value = value == null ? ProducerDocument.parse("null") : value.deepCopy();
		alternatives = List.copyOf(alternatives);
	}

	@Override
	public JsonNode value() {
		return value.deepCopy();
	}

	public static JudgeTruth proposition(@Nullable Boolean value) {
		return simple("PROPOSITION", value);
	}

	public static JudgeTruth status(@Nullable String value) {
		return simple("STATUS_LABEL", value);
	}

	public static JudgeTruth simple(String kind, Object value) {
		return new JudgeTruth(kind, ProducerDocument.parse(ProducerDocument.json(value)), null, null, null, null, null,
				List.of());
	}

	public String label() {
		return value.isNull() ? null : value.asText();
	}
}
