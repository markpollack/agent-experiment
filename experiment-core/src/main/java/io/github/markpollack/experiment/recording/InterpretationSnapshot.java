package io.github.markpollack.experiment.recording;

import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.judge.jury.interpretation.Interpretation;
import io.github.markpollack.judge.jury.interpretation.Verdicts;
import org.jspecify.annotations.Nullable;

/** One bound current reading, or a separately labeled original historical reading. */
public record InterpretationSnapshot(String state, String sourceSha256, @Nullable String producerRevision,
		@Nullable String interpreterArtifactSha256, JsonNode document) {
	public InterpretationSnapshot {
		if (!"VERIFIED".equals(state) && !"LEGACY_UNVERIFIED".equals(state))
			throw new IllegalArgumentException("Unknown interpretation state");
		java.util.Objects.requireNonNull(sourceSha256, "sourceSha256");
		document = document.deepCopy();
		if (!document.isObject() && !(state.equals("LEGACY_UNVERIFIED") && document.isNull()))
			throw new IllegalArgumentException("Interpretation must be an object");
		if (state.equals("VERIFIED") && (producerRevision == null || interpreterArtifactSha256 == null))
			throw new IllegalArgumentException("Verified interpretation needs runtime identity");
	}

	@Override
	public JsonNode document() {
		return document.deepCopy();
	}

	/** Compute from frozen source, never from an unrelated live object. */
	public static InterpretationSnapshot capture(ProducerDocument source, RuntimeIdentity runtime) {
		return new InterpretationSnapshot("VERIFIED", source.sha256(), runtime.revision(),
				runtime.artifacts().get("agent-judge-core"),
				ProducerDocument.parse(ProducerDocument.json(Verdicts.interpret(source.producerMap()))));
	}

	/** Check the exact source and interpreter before using a current reading. */
	public void validate(ProducerDocument source, RuntimeIdentity runtime) {
		if (!sourceSha256.equals(source.sha256()))
			throw new IllegalArgumentException("Interpretation source mismatch");
		if (state.equals("LEGACY_UNVERIFIED"))
			return;
		if (!producerRevision.equals(runtime.revision())
				|| !interpreterArtifactSha256.equals(runtime.artifacts().get("agent-judge-core"))
				|| !document.equals(capture(source, runtime).document))
			throw new IllegalArgumentException("Interpretation binding or reading mismatch");
	}

	/** Compatibility view; original historical JSON remains in document. */
	public @Nullable Interpretation reading() {
		if (document.isNull())
			return null;
		try {
			return new ObjectMapper().readerFor(Interpretation.class)
				.without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
				.readValue(document);
		}
		catch (Exception e) {
			throw new IllegalArgumentException("Invalid interpretation document", e);
		}
	}
}
