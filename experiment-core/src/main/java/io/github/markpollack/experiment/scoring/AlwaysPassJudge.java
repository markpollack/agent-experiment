package io.github.markpollack.experiment.scoring;

import io.github.markpollack.judge.Judge;
import io.github.markpollack.experiment.scoring.AgentJudgingInput;
import io.github.markpollack.judge.judgment.Judgment;

/**
 * Placeholder judge that always returns PASS. Used as the final tier in a
 * {@link io.github.markpollack.judge.jury.CascadedJury} until real Tier 2/3 judges are
 * implemented.
 */
class AlwaysPassJudge implements io.github.markpollack.judge.JudgeWithMetadata {

	AlwaysPassJudge() {
	}

	@Override
	public io.github.markpollack.judge.JudgeMetadata metadata() {
		return new io.github.markpollack.judge.JudgeMetadata("always_pass", "Fixture placeholder",
				io.github.markpollack.judge.JudgeType.DETERMINISTIC);
	}

	@Override
	public Judgment judge() {
		return Judgment.builder()
			.pass()
			.reasoning("Placeholder — always passes (Tier 2/3 judges not yet implemented)")
			.build();
	}

}
