/**
 * Run-journal lifecycle for agent experiments — the experiment-core slice of the
 * cross-repo first-class journal-capture feature.
 *
 * <p>
 * {@code AgentExperiment} owns this lifecycle so journaling is a <em>property of running
 * an experiment</em>, not an opt-in the author must remember (the v3/v4 blind spot). Per
 * item it opens an
 * {@link io.github.markpollack.experiment.journal.ExperimentJournal#openItem run journal}
 * on durable {@code JsonFileStorage}, records each returned {@code PhaseCapture}
 * (auto-emitting derived per-step {@code StepCostEvent}s to {@code analysis.jsonl}), and
 * finishes it. The invoker stays dumb: it only returns {@code PhaseCapture}; nothing here
 * pushes journaling into invokers.
 *
 * @see io.github.markpollack.experiment.journal.ExperimentJournal
 * @see io.github.markpollack.experiment.journal.RunJournal
 */
@NullMarked
package io.github.markpollack.experiment.journal;

import org.jspecify.annotations.NullMarked;
