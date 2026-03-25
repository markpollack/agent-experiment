package io.github.markpollack.experiment.scoring;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springaicommunity.judge.context.ExecutionStatus;
import org.springaicommunity.judge.context.JudgmentContext;
import org.springaicommunity.judge.result.Judgment;
import org.springaicommunity.judge.result.JudgmentStatus;
import org.springaicommunity.judge.score.BooleanScore;

import static org.assertj.core.api.Assertions.assertThat;

class AlwaysPassJudgeTest {

	private final AlwaysPassJudge judge = new AlwaysPassJudge();

	@Test
	void returnsPassStatus() {
		Judgment judgment = judge.judge(context());

		assertThat(judgment.status()).isEqualTo(JudgmentStatus.PASS);
	}

	@Test
	void returnsBooleanScoreTrue() {
		Judgment judgment = judge.judge(context());

		assertThat(judgment.score()).isInstanceOf(BooleanScore.class);
		assertThat(((BooleanScore) judgment.score()).value()).isTrue();
	}

	@Test
	void hasReasoningText() {
		Judgment judgment = judge.judge(context());

		assertThat(judgment.reasoning()).contains("Placeholder");
	}

	@Test
	void hasCorrectMetadataName() {
		assertThat(judge.metadata().name()).isEqualTo("always_pass");
	}

	private static JudgmentContext context() {
		return JudgmentContext.builder()
			.goal("test")
			.workspace(Path.of("/tmp"))
			.status(ExecutionStatus.SUCCESS)
			.startedAt(Instant.now())
			.executionTime(Duration.ofSeconds(1))
			.metadata(Map.of())
			.build();
	}

}
