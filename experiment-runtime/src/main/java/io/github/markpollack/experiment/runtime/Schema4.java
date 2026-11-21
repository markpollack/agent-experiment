package io.github.markpollack.experiment.runtime;

import java.io.IOException;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Reads schema 4 JSON into the schema 5 record shape without changing its meaning.
 *
 * <p>
 * Schema 4 recorded Workflow execution only. Each of its attempts carried Workflow
 * coordinates as three top-level fields, and each row carried a {@code workflowState}.
 * This class moves those values into their schema 5 places: an attempt becomes a
 * {@code WORKFLOW} attempt with the same coordinates, and a row becomes a
 * {@code WORKFLOW} row with the same state and proof, or {@code NOT_STARTED}. It never
 * produces a direct attempt or row, and it adds no value that the file does not hold. The
 * result keeps {@code recordingSchemaVersion} 4.
 * </p>
 */
final class Schema4 {

	private static final List<String> COORDINATES = List.of("workflowRunId", "workflowInvocationId",
			"workflowAttemptId");

	private Schema4() {
	}

	static JsonNode upgradeResult(JsonNode original) throws IOException {
		JsonNode tree = original.deepCopy();
		for (JsonNode item : tree.path("items")) {
			ObjectNode row = object(item, "row");
			if (row.has("execution") || row.has("executionState") || !row.path("workflowState").isTextual())
				throw new IOException("Malformed schema 4 row");
			String state = row.remove("workflowState").textValue();
			row.put("execution", state.equals("NOT_STARTED") ? "NOT_STARTED" : "WORKFLOW");
			row.put("executionState", state);
			for (JsonNode invocation : row.path("invocations"))
				upgradeAttempt(object(invocation, "invocation").path("attempt"));
			for (JsonNode proof : row.path("workflowProof"))
				for (JsonNode attempt : proof.path("attempts"))
					upgradeAttempt(attempt);
		}
		return tree;
	}

	/**
	 * Upgrades a retained schema 4 attempt in place; a schema 5 attempt, which always
	 * states its execution, is left as it is.
	 */
	static void upgradeRetainedAttempt(JsonNode attempt) throws IOException {
		if (!attempt.has("execution"))
			upgradeAttempt(attempt);
	}

	private static void upgradeAttempt(JsonNode node) throws IOException {
		ObjectNode attempt = object(node, "attempt");
		if (attempt.has("execution") || attempt.has("workflow"))
			throw new IOException("Malformed schema 4 attempt");
		ObjectNode coordinates = attempt.objectNode();
		for (String key : COORDINATES) {
			JsonNode value = attempt.remove(key);
			if (value == null || !value.isTextual())
				throw new IOException("Schema 4 attempt without its Workflow coordinates: " + key);
			coordinates.set(switch (key) {
				case "workflowRunId" -> "runId";
				case "workflowInvocationId" -> "invocationId";
				default -> "attemptId";
			}, value);
		}
		attempt.put("execution", "WORKFLOW");
		ArrayNode workflow = attempt.putArray("workflow");
		workflow.add(coordinates);
	}

	private static ObjectNode object(JsonNode node, String what) throws IOException {
		if (!(node instanceof ObjectNode object))
			throw new IOException("Malformed schema 4 " + what);
		return object;
	}

}
