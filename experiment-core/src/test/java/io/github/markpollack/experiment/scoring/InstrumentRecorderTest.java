package io.github.markpollack.experiment.scoring;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.markpollack.experiment.result.InstrumentRecord;
import io.github.markpollack.judge.Judge;
import io.github.markpollack.judge.Judges;
import io.github.markpollack.judge.context.JudgmentContext;
import io.github.markpollack.judge.description.JuryDescription;
import io.github.markpollack.judge.jury.ConsensusStrategy;
import io.github.markpollack.judge.jury.Jury;
import io.github.markpollack.judge.jury.SimpleJury;
import io.github.markpollack.judge.jury.Verdict;
import io.github.markpollack.judge.jury.VotingStrategy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InstrumentRecorderTest {

	@Test
	void describesTheJuryBeforeItVotes() {
		InstrumentRecord instrument = InstrumentRecorder
			.describe(jury(1.0, Judges.named(Judges.alwaysPass("built"), "build"), Judges.alwaysPass("smoke")));

		assertThat(instrument.describeFailure()).isNull();
		assertThat(instrument.descriptionVersion()).isEqualTo(JuryDescription.DESCRIPTION_VERSION);
		assertThat(instrument.specHash()).matches("[0-9a-f]{64}");
		assertThat(instrument.description()).containsEntry("kind", "SIMPLE");
		assertThat((List<?>) instrument.description().get("seats")).hasSize(2);
		assertThat(instrument.libraries()).containsKey("agent-judge-core");
		// A snapshot version names no particular build, so the jar's own bytes are what
		// identify the code that scored the run.
		assertThat(instrument.artifacts().get("agent-judge-core")).matches("sha256:[0-9a-f]{64}");
	}

	@Test
	void sameConfigurationHashesTheSameAndAChangedWeightDoesNot() {
		String first = InstrumentRecorder.describe(jury(1.0, Judges.named(Judges.alwaysPass("a"), "a"))).specHash();
		String again = InstrumentRecorder.describe(jury(1.0, Judges.named(Judges.alwaysPass("a"), "a"))).specHash();
		String reweighted = InstrumentRecorder.describe(jury(2.0, Judges.named(Judges.alwaysPass("a"), "a")))
			.specHash();

		assertThat(again).isEqualTo(first);
		assertThat(reweighted).isNotEqualTo(first);
	}

	@Test
	void hashDoesNotDependOnMapInsertionOrder() {
		Map<String, Object> forward = new LinkedHashMap<>();
		forward.put("a", 1);
		forward.put("b", Map.of("y", 2, "x", 3));
		Map<String, Object> backward = new LinkedHashMap<>();
		backward.put("b", Map.of("x", 3, "y", 2));
		backward.put("a", 1);

		assertThat(InstrumentRecorder.hash(backward)).isEqualTo(InstrumentRecorder.hash(forward));
	}

	@Test
	void juryThatCannotBeDescribedIsRecordedAsSuchNotAsAnEmptyJury() {
		Jury undescribable = new Jury() {
			@Override
			public List<Judge> getJudges() {
				return List.of();
			}

			@Override
			public VotingStrategy getVotingStrategy() {
				return new ConsensusStrategy();
			}

			@Override
			public Verdict vote(JudgmentContext context) {
				throw new UnsupportedOperationException();
			}

			@Override
			public JuryDescription describe() {
				throw new IllegalArgumentException("seats[0].weight: not finite");
			}
		};

		InstrumentRecord instrument = InstrumentRecorder.describe(undescribable);

		assertThat(instrument.describeFailure()).contains("seats[0].weight");
		assertThat(instrument.description()).isNull();
		assertThat(instrument.specHash()).isNull();
		assertThat(instrument.descriptionVersion()).isNull();
	}

	private static SimpleJury jury(double firstWeight, Judge first, Judge... rest) {
		SimpleJury.Builder builder = SimpleJury.builder()
			.votingStrategy(new ConsensusStrategy())
			.parallel(false)
			.judge(first, firstWeight);
		for (Judge judge : rest) {
			builder.judge(judge);
		}
		return builder.build();
	}

}
