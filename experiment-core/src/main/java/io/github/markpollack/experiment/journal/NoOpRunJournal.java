package io.github.markpollack.experiment.journal;

import io.github.markpollack.journal.claude.PhaseCapture;

/**
 * A {@link RunJournal} that does nothing. Cheap and safe: the default when journaling is
 * disabled ({@code withoutJournal()}) or there is no output directory to write a durable
 * journal into.
 */
enum NoOpRunJournal implements RunJournal {

	INSTANCE;

	@Override
	public void recordPhase(PhaseCapture phase) {
		// no-op
	}

	@Override
	public void finish() {
		// no-op
	}

	@Override
	public void close() {
		// no-op
	}

}
