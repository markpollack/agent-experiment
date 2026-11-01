package io.github.markpollack.experiment.runtime;

import java.nio.charset.StandardCharsets;
import java.util.List;
import io.github.markpollack.judge.jury.Jury;
import io.github.markpollack.judge.verdict.Verdict;
import io.github.markpollack.experiment.runtime.AgentRecords.*;

/**
 * Judge owns serialization and conclusion semantics; Experiment retains the complete
 * document.
 */
public final class JudgeRecording {

	private JudgeRecording() {
	}

	@FunctionalInterface
	public interface ItemJuryFactory {

		Jury create(PreparedItem prepared, Domain measured) throws Exception;

	}

	public static VerdictDocument capture(Verdict verdict) {
		try {
			String text = RuntimeJson.mapper().writeValueAsString(verdict);
			return new VerdictDocument(text, Evidence.sha256(text.getBytes(StandardCharsets.UTF_8)),
					verdict.conclusion().name(), "agent-judge-v6:0.18.0");
		}
		catch (Exception e) {
			throw new IllegalArgumentException("Cannot retain complete Judge verdict", e);
		}
	}

	public static Verdict read(VerdictDocument document) {
		try {
			// Judge V6 omits its optional fields; keep that producer-owned codec contract
			// separate from mandatory Experiment envelope fields.
			return RuntimeJson.mapper()
				.disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
				.readValue(document.producerJson(), Verdict.class);
		}
		catch (Exception e) {
			throw new IllegalArgumentException("Unsupported Judge document", e);
		}
	}

	public static void validate(VerdictDocument document) {
		AgentRecords.require(read(document).conclusion().name().equals(document.conclusion()),
				"Verdict conclusion mismatch");
	}

	public static Row assess(PreparedItem prepared, List<Invocation> invocations, String workflowState, Domain measured,
			ItemJuryFactory factory, List<WorkflowProof> proof) {
		try {
			Jury jury = java.util.Objects.requireNonNull(factory.create(prepared, measured));
			VerdictDocument verdict = capture(java.util.Objects.requireNonNull(jury.vote()));
			return new Row(prepared.identity(), invocations, workflowState, measured, List.of(verdict), "RETURNED", "",
					"RETAINED", proof);
		}
		catch (Exception e) {
			return new Row(prepared.identity(), invocations, workflowState, measured, List.of(), "THREW",
					e.getClass().getName() + ": " + String.valueOf(e.getMessage()), "RETAINED", proof);
		}
	}

}
