package io.github.markpollack.experiment.scoring;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import io.github.markpollack.experiment.agent.TerminalStatus;
import io.github.markpollack.experiment.scoring.AgentJudgingInput;
import io.github.markpollack.judge.judgment.Judgment;
import io.github.markpollack.judge.judgment.JudgmentStatus;

import static org.assertj.core.api.Assertions.assertThat;

class AlwaysPassJudgeTest {

	private final AlwaysPassJudge judge = new AlwaysPassJudge();

	@Test
	void returnsPassStatus() {
		Judgment judgment = judge.judge();

		assertThat(judgment.status()).isEqualTo(JudgmentStatus.PASS);
	}

	@Test
	void projectsBooleanOutcomeThroughEffectiveScore() {
		Judgment judgment = judge.judge();

		assertThat(judgment.score()).isNull();
		assertThat(judgment.effectiveScore()).hasValue(1.0);
	}

	@Test
	void hasReasoningText() {
		Judgment judgment = judge.judge();

		assertThat(judgment.reasoning()).contains("Placeholder");
	}

	@Test
	void hasCorrectMetadataName() {
		assertThat(judge.metadata().name()).isEqualTo("always_pass");
	}

	private static AgentJudgingInput context() {
		return AgentJudgingInput.builder()
			.goal("test")
			.workspace(Path.of("/tmp"))
			.status(TerminalStatus.COMPLETED)
			.executionTime(Duration.ofSeconds(1))

			.build();
	}

}
