package io.github.markpollack.experiment.scoring;

import io.github.markpollack.judge.DeterministicJudge;
import io.github.markpollack.judge.context.JudgmentContext;
import io.github.markpollack.judge.result.Judgment;

/**
 * Placeholder judge that always returns PASS. Used as the final tier in a
 * {@link io.github.markpollack.judge.jury.CascadedJury} until real Tier 2/3 judges are
 * implemented.
 */
class AlwaysPassJudge extends DeterministicJudge {

	AlwaysPassJudge() {
		super("always_pass", "Placeholder judge that always passes");
	}

	@Override
	public Judgment judge(JudgmentContext context) {
		return Judgment.builder()
			.pass()
			.reasoning("Placeholder — always passes (Tier 2/3 judges not yet implemented)")
			.build();
	}

}
