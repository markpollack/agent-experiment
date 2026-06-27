package io.github.markpollack.experiment.journal;

import io.github.markpollack.journal.claude.PhaseCapture;
import io.github.markpollack.journal.claude.RunRecorder;

/**
 * A {@link RunJournal} backed by agent-journal's production {@link RunRecorder} on
 * durable {@link io.github.markpollack.journal.storage.JsonFileStorage}. Each
 * {@link #recordPhase} delegates to {@code RunRecorder.recordPhase}, which auto-emits the
 * derived per-step {@code StepCostEvent}s (DESIGN §4); {@link #finish} runs the fail-loud
 * durability check.
 */
final class JsonFileRunJournal implements RunJournal {

	private final RunRecorder recorder;

	JsonFileRunJournal(RunRecorder recorder) {
		this.recorder = recorder;
	}

	@Override
	public void recordPhase(PhaseCapture phase) {
		recorder.recordPhase(phase);
	}

	@Override
	public void finish() {
		recorder.finish();
	}

	@Override
	public void close() {
		recorder.close();
	}

}
