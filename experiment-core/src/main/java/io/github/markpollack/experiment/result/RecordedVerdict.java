package io.github.markpollack.experiment.result;

import java.util.*;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.markpollack.experiment.recording.ProducerDocument;
import io.github.markpollack.judge.jury.Verdict;
import org.jspecify.annotations.Nullable;

/** Complete producer verdict source; consumer instrument associations are external. */
public final class RecordedVerdict {

	private final ProducerDocument source;

	private RecordedVerdict(ProducerDocument source) {
		if (!source.sourceKind().equals("VERDICT"))
			throw new IllegalArgumentException("Expected VERDICT source");
		this.source = source;
	}

	/** Explicit historical projection constructor, retained for source compatibility. */
	public RecordedVerdict(RecordedJudgment aggregated, List<RecordedJudgment> individual,
			Map<String, RecordedJudgment> individualByName, Map<String, Double> weights,
			@Nullable List<RecordedSeat> seats, @Nullable RecordedDecision decision,
			List<RecordedCompositeAttempt> compositeAttempts, @Nullable String instrumentHash) {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("aggregated", Objects.requireNonNull(aggregated).document().node());
		values.put("individual", individual.stream().map(v -> v.document().node()).toList());
		Map<String, Object> named = new LinkedHashMap<>();
		individualByName.forEach((k, v) -> named.put(k, v.document().node()));
		values.put("individualByName", named);
		values.put("weights", weights);
		if (seats != null)
			values.put("seats", seats);
		if (decision != null)
			values.put("decision", decision);
		List<Map<String, Object>> attempts = new ArrayList<>();
		for (RecordedCompositeAttempt a : compositeAttempts) {
			Map<String, Object> v = new LinkedHashMap<>();
			v.put("name", a.name());
			v.put("relation", a.relation());
			if (a.policy() != null)
				v.put("policy", a.policy());
			if (a.disposition() != null)
				v.put("disposition", a.disposition());
			if (a.dispositionReason() != null)
				v.put("dispositionReason", a.dispositionReason());
			if (a.verdict() != null)
				v.put("verdict", a.verdict().document().node());
			if (a.failureCode() != null)
				v.put("failureCode", a.failureCode());
			attempts.add(v);
		}
		values.put("compositeAttempts", attempts);
		ProducerDocument doc = ProducerDocument.from("VERDICT", values);
		this.source = instrumentHash == null ? doc
				: doc.withAssociations(List.of(new ProducerDocument.Association("", instrumentHash)));
	}

	public RecordedVerdict(RecordedJudgment a, List<RecordedJudgment> i, Map<String, RecordedJudgment> n,
			Map<String, Double> w, List<RecordedCompositeAttempt> c) {
		this(a, i, n, w, null, null, c, null);
	}

	public RecordedVerdict(RecordedJudgment a, List<RecordedJudgment> i, Map<String, RecordedJudgment> n,
			Map<String, Double> w, List<RecordedCompositeAttempt> c, @Nullable String h) {
		this(a, i, n, w, null, null, c, h);
	}

	public static RecordedVerdict from(Verdict value) {
		return from(value, null);
	}

	public static RecordedVerdict from(Verdict value, @Nullable String instrumentHash) {
		ProducerDocument doc = ProducerDocument.from("VERDICT", value);
		return new RecordedVerdict(instrumentHash == null ? doc
				: doc.withAssociations(List.of(new ProducerDocument.Association("", instrumentHash))));
	}

	public static RecordedVerdict fromProducerJson(String text) {
		return new RecordedVerdict(ProducerDocument.text("VERDICT", text));
	}

	public static RecordedVerdict fromDocument(ProducerDocument source) {
		return new RecordedVerdict(source);
	}

	public RecordedVerdict withAssociations(List<ProducerDocument.Association> associations) {
		return new RecordedVerdict(source.withAssociations(associations));
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

	public RecordedJudgment aggregated() {
		return RecordedJudgment.fromProducerJson(source.node().path("aggregated").toString());
	}

	public List<RecordedJudgment> individual() {
		List<RecordedJudgment> result = new ArrayList<>();
		source.node().path("individual").forEach(n -> result.add(RecordedJudgment.fromProducerJson(n.toString())));
		return List.copyOf(result);
	}

	public Map<String, RecordedJudgment> individualByName() {
		Map<String, RecordedJudgment> result = new LinkedHashMap<>();
		source.node()
			.path("individualByName")
			.properties()
			.forEach(e -> result.put(e.getKey(), RecordedJudgment.fromProducerJson(e.getValue().toString())));
		return Collections.unmodifiableMap(result);
	}

	public Map<String, Double> weights() {
		Map<String, Double> result = new LinkedHashMap<>();
		source.node().path("weights").properties().forEach(e -> result.put(e.getKey(), e.getValue().doubleValue()));
		return Collections.unmodifiableMap(result);
	}

	public @Nullable List<RecordedSeat> seats() {
		if (!source.node().hasNonNull("seats"))
			return null;
		List<RecordedSeat> result = new ArrayList<>();
		for (JsonNode n : source.node().path("seats"))
			result.add(new RecordedSeat(n.path("position").intValue(), n.path("verdictKey").asText(),
					n.path("keySource").textValue()));
		return List.copyOf(result);
	}

	public @Nullable RecordedDecision decision() {
		JsonNode n = source.node().path("decision");
		return n.isObject()
				? new RecordedDecision(n.path("kind").asText(), n.path("tier").textValue(), n.path("basis").textValue())
				: null;
	}

	public List<RecordedCompositeAttempt> compositeAttempts() {
		List<RecordedCompositeAttempt> result = new ArrayList<>();
		for (JsonNode n : source.node().path("compositeAttempts")) {
			result.add(new RecordedCompositeAttempt(n.path("name").asText(), n.path("relation").asText(),
					n.path("policy").textValue(), n.path("disposition").textValue(),
					n.path("dispositionReason").textValue(),
					n.hasNonNull("verdict") ? fromProducerJson(n.get("verdict").toString()) : null,
					n.hasNonNull("failureCode") ? n.get("failureCode").textValue()
							: n.path("failure").path("code").textValue()));
		}
		int index = 0;
		for (JsonNode n : source.node().path("subVerdicts"))
			result.add(RecordedCompositeAttempt.legacy(index++, fromProducerJson(n.toString())));
		return List.copyOf(result);
	}

	public @Nullable String instrumentHash() {
		return source.associations()
			.stream()
			.filter(a -> a.verdictPointer().isEmpty())
			.map(ProducerDocument.Association::instrumentHash)
			.findFirst()
			.orElse(source.node().path("instrumentHash").textValue());
	}

	@Override
	public boolean equals(Object value) {
		return value instanceof RecordedVerdict other && source.equals(other.source);
	}

	@Override
	public int hashCode() {
		return source.hashCode();
	}

	@Override
	public String toString() {
		return "RecordedVerdict[sha256=" + sha256() + "]";
	}

}
