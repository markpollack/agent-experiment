package io.github.markpollack.experiment.result.history;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.experiment.result.RecordedDecision;
import io.github.markpollack.experiment.result.RecordedJudgmentStatus;
import org.jspecify.annotations.Nullable;

/**
 * Reads stored results as history: what the file actually says, including what it does
 * not say.
 *
 * <p>
 * The live types demand every fact the contract requires, which is right for something
 * being written now and wrong for something written a year ago. This reader never
 * defaults a missing fact; it decodes into the historical types, where an absence stays
 * an absence and a caller decides what to do about it.
 */
public final class HistoricalResults {

	private static final ObjectMapper JSON = new ObjectMapper();

	private HistoricalResults() {
	}

	/** Every stored item's verdict in a result file, keyed by item id. */
	public static Map<String, HistoricalVerdict> verdictsByItem(Path resultFile) throws IOException {
		JsonNode root = JSON.readTree(Files.readString(resultFile));
		Map<String, HistoricalVerdict> verdicts = new LinkedHashMap<>();
		for (JsonNode item : root.path("items")) {
			JsonNode verdict = item.get("verdict");
			if (verdict != null && !verdict.isNull()) {
				verdicts.put(item.path("itemId").asText(""), verdict(verdict));
			}
		}
		return verdicts;
	}

	/** Read one stored verdict, keeping every absence. */
	public static HistoricalVerdict verdict(JsonNode node) {
		List<HistoricalJudgment> individual = new ArrayList<>();
		for (JsonNode judgment : node.path("individual")) {
			individual.add(judgment(judgment));
		}
		Map<String, HistoricalJudgment> byName = new LinkedHashMap<>();
		node.path("individualByName").properties().forEach(e -> byName.put(e.getKey(), judgment(e.getValue())));

		// A weight that is not a number was not recorded as a weight. Reading it as 0.0
		// would silence a judge that may have carried the vote.
		Map<String, Double> weights = new LinkedHashMap<>();
		node.path("weights").properties().forEach(e -> {
			if (e.getValue() != null && e.getValue().isNumber()) {
				weights.put(e.getKey(), e.getValue().doubleValue());
			}
		});

		// Absent stays absent: no seat list at all is not the same as a recorded empty
		// one.
		List<HistoricalSeat> seats = null;
		if (node.hasNonNull("seats")) {
			seats = new ArrayList<>();
			for (JsonNode seat : node.get("seats")) {
				// A position that is present but not a number was not recorded as a
				// position. asInt() would turn "unknown" into seat zero.
				JsonNode position = seat.get("position");
				seats.add(new HistoricalSeat(position != null && position.isInt() ? position.intValue() : null,
						text(seat.get("verdictKey")), text(seat.get("keySource"))));
			}
		}

		RecordedDecision decision = null;
		if (node.hasNonNull("decision")) {
			JsonNode d = node.get("decision");
			decision = new RecordedDecision(d.path("kind").asText(""), text(d.get("tier")), text(d.get("basis")));
		}

		List<HistoricalCompositeAttempt> attempts = new ArrayList<>();
		for (JsonNode attempt : node.path("compositeAttempts")) {
			attempts.add(new HistoricalCompositeAttempt(attempt.path("name").asText(""),
					attempt.path("relation").asText(""), text(attempt.get("policy")), text(attempt.get("disposition")),
					text(attempt.get("dispositionReason")),
					attempt.hasNonNull("verdict") ? verdict(attempt.get("verdict")) : null,
					text(attempt.get("failureCode"))));
		}

		return new HistoricalVerdict(judgment(node.path("aggregated")), individual, byName, weights, seats, decision,
				attempts, text(node.get("instrumentHash")));
	}

	private static HistoricalJudgment judgment(JsonNode node) {
		List<HistoricalCheck> checks = new ArrayList<>();
		for (JsonNode check : node.path("checks")) {
			// A check with no recorded outcome is not a failed check, and neither is one
			// whose outcome is present but unreadable: asBoolean() turns "unknown" into
			// false.
			JsonNode passed = check.get("passed");
			checks.add(new HistoricalCheck(check.path("name").asText(""),
					passed != null && passed.isBoolean() ? passed.booleanValue() : null,
					check.path("message").asText("")));
		}
		Map<String, Object> metadata = node.hasNonNull("metadata")
				? JSON.convertValue(node.get("metadata"), new TypeReference<Map<String, Object>>() {
				}) : Map.of();
		// A status this reader has never heard of is an absence, not a crash: the whole
		// point of a historical read is that it survives what it does not recognise.
		RecordedJudgmentStatus status = null;
		if (node.hasNonNull("status")) {
			try {
				status = RecordedJudgmentStatus.fromWire(node.get("status").asText());
			}
			catch (IllegalArgumentException ex) {
				status = null;
			}
		}
		Double score = node.hasNonNull("score") && node.get("score").isNumber() ? node.get("score").doubleValue()
				: null;
		return new HistoricalJudgment(status, score, text(node.get("label")), text(node.get("reasonCode")),
				node.path("reasoning").asText(""), checks, metadata);
	}

	private static @Nullable String text(@Nullable JsonNode node) {
		return node == null || node.isNull() ? null : node.asText();
	}

}
