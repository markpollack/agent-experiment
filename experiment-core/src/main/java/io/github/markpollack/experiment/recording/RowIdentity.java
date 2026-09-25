package io.github.markpollack.experiment.recording;

import java.nio.charset.StandardCharsets;

/** One declared case, candidate and replicate in one run. */
public record RowIdentity(String runId, String caseId, String candidateId, int replicateIndex, String rowId) {
	public RowIdentity {
		if (runId == null || runId.isBlank() || caseId == null || caseId.isBlank() || candidateId == null
				|| candidateId.isBlank() || replicateIndex < 0)
			throw new IllegalArgumentException("Invalid row identity");
		if (!id(caseId, candidateId, replicateIndex).equals(rowId))
			throw new IllegalArgumentException("Row ID does not encode declaration");
	}

	public static RowIdentity of(String run, String item, String candidate, int replicate) {
		return new RowIdentity(run, item, candidate, replicate, id(item, candidate, replicate));
	}

	private static String id(String item, String candidate, int replicate) {
		return part(item) + part(candidate) + part(Integer.toString(replicate));
	}

	private static String part(String text) {
		return text.getBytes(StandardCharsets.UTF_8).length + ":" + text;
	}
}
