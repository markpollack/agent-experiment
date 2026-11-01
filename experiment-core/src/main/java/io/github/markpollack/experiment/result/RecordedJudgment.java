package io.github.markpollack.experiment.result;

import java.util.*;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.markpollack.experiment.recording.ProducerDocument;
import io.github.markpollack.judge.judgment.Judgment;
import org.jspecify.annotations.Nullable;

/** Complete immutable producer source with derived legacy convenience views. */
public final class RecordedJudgment {

	private final ProducerDocument source;

	private RecordedJudgment(ProducerDocument source) {
		if (!source.sourceKind().equals("JUDGMENT"))
			throw new IllegalArgumentException("Expected JUDGMENT source");
		this.source = source;
	}

	/** Record an explicitly legacy projection. New live values use from(Judgment). */
	public RecordedJudgment(RecordedJudgmentStatus status, @Nullable Double score, @Nullable String label,
			@Nullable String reasonCode, String reasoning, List<RecordedCheck> checks, Map<String, Object> metadata) {
		Objects.requireNonNull(status);
		Objects.requireNonNull(reasoning);
		if (score != null && (!Double.isFinite(score) || score < 0 || score > 1))
			throw new IllegalArgumentException("score must be finite and between 0 and 1");
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("status", status.wireName());
		if (score != null)
			values.put("score", score);
		if (label != null)
			values.put("label", label);
		if (reasonCode != null)
			values.put("reasonCode", reasonCode);
		values.put("reasoning", reasoning);
		values.put("checks", checks);
		values.put("metadata", metadata);
		this.source = ProducerDocument.from("JUDGMENT", values);
	}

	public RecordedJudgment(RecordedJudgmentStatus status, @Nullable Double score, @Nullable String label,
			String reasoning, List<RecordedCheck> checks, Map<String, Object> metadata) {
		this(status, score, label, null, reasoning, checks, metadata);
	}

	public static RecordedJudgment from(Judgment value) {
		return new RecordedJudgment(ProducerDocument.from("JUDGMENT", value));
	}

	public static RecordedJudgment fromProducerJson(String text) {
		return new RecordedJudgment(ProducerDocument.text("JUDGMENT", text));
	}

	public static RecordedJudgment fromDocument(ProducerDocument document) {
		return new RecordedJudgment(document);
	}

	@JsonValue
	public ProducerDocument document() {
		return source;
	}

	public String producerJson() {
		return source.producerJson();
	}

	public String sha256() {
		return source.sha256();
	}

	private boolean modern() {
		return source.node().has("schemaVersion") || source.node().has("producerStatus");
	}

	private Judgment live() {
		return source.value(Judgment.class);
	}

	public RecordedJudgmentStatus status() {
		return modern() ? RecordedJudgmentStatus.from(live().status())
				: RecordedJudgmentStatus.fromWire(source.node().path("status").asText());
	}

	public @Nullable Double score() {
		if (modern())
			return live().score();
		JsonNode score = source.node().path("score");
		if (score.isNumber())
			return score.doubleValue();
		if (score.isObject() && score.path("value").isNumber() && score.path("min").isNumber()
				&& score.path("max").isNumber()) {
			double min = score.path("min").doubleValue(), max = score.path("max").doubleValue();
			if (max <= min)
				throw new IllegalArgumentException("Invalid legacy numeric scale");
			return (score.path("value").doubleValue() - min) / (max - min);
		}
		return null;
	}

	public @Nullable String label() {
		if (modern())
			return live().label();
		JsonNode node = source.node();
		return node.hasNonNull("label") ? node.get("label").textValue()
				: node.path("score").path("value").isTextual() ? node.path("score").path("value").textValue() : null;
	}

	public @Nullable String reasonCode() {
		if (modern())
			return live().reasonCode() == null ? null : live().reasonCode().wireName();
		return source.node().path("reasonCode").textValue();
	}

	public String reasoning() {
		return source.node().path("reasoning").asText("");
	}

	public List<RecordedCheck> checks() {
		if (modern())
			return live().checks().stream().map(RecordedCheck::from).toList();
		List<RecordedCheck> result = new ArrayList<>();
		for (JsonNode node : source.node().path("checks")) {
			result.add(new RecordedCheck(node.path("name").asText(),
					node.path("passed").isBoolean() ? node.path("passed").booleanValue() : null,
					node.path("message").asText(""), null));
		}
		return List.copyOf(result);
	}

	@SuppressWarnings("unchecked")
	public Map<String, Object> metadata() {
		Object value = source.producerMap().get("metadata");
		return value instanceof Map<?, ?> ? Collections.unmodifiableMap((Map<String, Object>) value) : Map.of();
	}

	public boolean notApplicable() {
		return status() == RecordedJudgmentStatus.NOT_APPLICABLE;
	}

	public boolean pass() {
		return status() == RecordedJudgmentStatus.PASS;
	}

	public OptionalDouble effectiveScore() {
		if (modern())
			return live().effectiveScore();
		Double score = score();
		if (score != null)
			return OptionalDouble.of(score);
		return switch (status()) {
			case PASS -> OptionalDouble.of(1);
			case FAIL -> OptionalDouble.of(0);
			default -> OptionalDouble.empty();
		};
	}

	@Override
	public boolean equals(Object value) {
		return value instanceof RecordedJudgment other && source.equals(other.source);
	}

	@Override
	public int hashCode() {
		return source.hashCode();
	}

	@Override
	public String toString() {
		return "RecordedJudgment[sha256=" + sha256() + "]";
	}

}
