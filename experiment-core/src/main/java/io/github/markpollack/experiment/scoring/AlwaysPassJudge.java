package io.github.markpollack.experiment.scoring;

import org.springaicommunity.judge.DeterministicJudge;
import org.springaicommunity.judge.context.JudgmentContext;
import org.springaicommunity.judge.result.Judgment;
import org.springaicommunity.judge.result.JudgmentStatus;
import org.springaicommunity.judge.score.BooleanScore;

/**
 * Placeholder judge that always returns PASS with a {@link BooleanScore}. Used as the
 * final tier in a {@link org.springaicommunity.judge.jury.CascadedJury} until real Tier
 * 2/3 judges are implemented.
 *
 * <p>
 * Important: the score must be set (not null) because
 * {@link org.springaicommunity.judge.jury.ConsensusStrategy} uses the score field, not
 * the status field, to determine pass/fail.
 */
class AlwaysPassJudge extends DeterministicJudge {

	AlwaysPassJudge() {
		super("always_pass", "Placeholder judge that always passes");
	}

	@Override
	public Judgment judge(JudgmentContext context) {
		return Judgment.builder()
			.score(new BooleanScore(true))
			.status(JudgmentStatus.PASS)
			.reasoning("Placeholder — always passes (Tier 2/3 judges not yet implemented)")
			.build();
	}

}
