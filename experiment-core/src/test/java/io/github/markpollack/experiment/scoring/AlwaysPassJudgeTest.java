package io.github.markpollack.experiment.scoring;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import io.github.markpollack.judge.context.ExecutionStatus;
import io.github.markpollack.judge.context.JudgmentContext;
import io.github.markpollack.judge.result.Judgment;
import io.github.markpollack.judge.result.JudgmentStatus;

import static org.assertj.core.api.Assertions.assertThat;

class AlwaysPassJudgeTest {

	private final AlwaysPassJudge judge = new AlwaysPassJudge();

	@Test
	void returnsPassStatus() {
		Judgment judgment = judge.judge(context());

		assertThat(judgment.status()).isEqualTo(JudgmentStatus.PASS);
	}

	@Test
	void projectsBooleanOutcomeThroughEffectiveScore() {
		Judgment judgment = judge.judge(context());

		assertThat(judgment.score()).isNull();
		assertThat(judgment.effectiveScore()).hasValue(1.0);
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
