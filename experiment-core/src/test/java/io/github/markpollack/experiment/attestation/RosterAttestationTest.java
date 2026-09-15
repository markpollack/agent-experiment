package io.github.markpollack.experiment.attestation;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.markpollack.experiment.result.ExperimentResult;
import io.github.markpollack.experiment.result.InstrumentRecord;
import io.github.markpollack.experiment.result.ItemResult;
import io.github.markpollack.experiment.result.RecordedCompositeAttempt;
import io.github.markpollack.experiment.result.RecordedJudgment;
import io.github.markpollack.experiment.result.RecordedJudgmentStatus;
import io.github.markpollack.experiment.result.RecordedVerdict;
import io.github.markpollack.experiment.scoring.InstrumentRecorder;
import io.github.markpollack.experiment.store.FileSystemResultStore;
import io.github.markpollack.judge.Judge;
import io.github.markpollack.judge.Judges;
import io.github.markpollack.judge.context.JudgmentContext;
import io.github.markpollack.judge.description.JuryDescription;
import io.github.markpollack.judge.jury.CascadedJury;
import io.github.markpollack.judge.jury.ConsensusStrategy;
import io.github.markpollack.judge.jury.Jury;
import io.github.markpollack.judge.jury.SimpleJury;
import io.github.markpollack.judge.jury.TierPolicy;
import io.github.markpollack.judge.jury.Verdict;
import io.github.markpollack.judge.jury.VotingStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class RosterAttestationTest {

	private static final JudgmentContext CONTEXT = JudgmentContext.builder().goal("roster").build();

	@Test
	void everyListedJudgeVotedSoTheDenominatorIsAttested() {
		SimpleJury jury = jury(Judges.alwaysPass("a"), Judges.alwaysPass("b"));
		InstrumentRecord instrument = InstrumentRecorder.describe(jury);

		ItemAttestation attestation = ItemAttestation.of(scored("a", jury, instrument), instrument);

		assertThat(attestation.attestability()).isEqualTo(Attestability.ATTESTED);
		assertThat(attestation.votes()).singleElement().satisfies(votes -> {
			assertThat(votes.rosterCount()).isEqualTo(2);
			assertThat(votes.inputCount()).isEqualTo(2);
		});
	}

	@Test
	void juryThatVotesWithFewerJudgesThanItListsIsCaught() {
		SimpleJury listed = jury(Judges.alwaysPass("a"), Judges.alwaysPass("b"), Judges.alwaysPass("c"));
		SimpleJury voting = jury(Judges.alwaysPass("a"), Judges.alwaysPass("b"));
		Jury silentlyShort = new Jury() {
			@Override
			public List<Judge> getJudges() {
				return listed.getJudges();
			}

			@Override
			public VotingStrategy getVotingStrategy() {
				return listed.getVotingStrategy();
			}

			@Override
			public Verdict vote(JudgmentContext context) {
				return voting.vote(context);
			}

			@Override
			public JuryDescription describe() {
				return listed.describe();
			}
		};
		InstrumentRecord instrument = InstrumentRecorder.describe(silentlyShort);

		ItemAttestation attestation = ItemAttestation.of(scored("a", silentlyShort, instrument), instrument);

		assertThat(attestation.outcome()).isEqualTo(RecordedJudgmentStatus.PASS);
		assertThat(attestation.attestability()).isEqualTo(Attestability.ROSTER_MISMATCH);
		assertThat(attestation.votes()).singleElement().satisfies(votes -> {
			assertThat(votes.rosterCount()).isEqualTo(3);
			assertThat(votes.inputCount()).isEqualTo(2);
		});
	}

	@Test
	void cascadeIsCheckedPerEnteredTierAndAnUnenteredTierIsNotAVote() {
		CascadedJury cascade = CascadedJury.builder()
			.tier("guardrail", jury(Judges.alwaysFail("broken")), TierPolicy.REJECT_ON_ANY_FAIL)
			.tier("quality", jury(Judges.alwaysPass("good"), Judges.alwaysPass("tidy")), TierPolicy.FINAL_TIER)
			.build();
		InstrumentRecord instrument = InstrumentRecorder.describe(cascade);

		ItemAttestation attestation = ItemAttestation.of(scored("a", cascade, instrument), instrument);

		assertThat(attestation.attestability()).isEqualTo(Attestability.ATTESTED);
		assertThat(attestation.votes()).singleElement().satisfies(votes -> {
			assertThat(votes.scope()).isEqualTo("guardrail");
			assertThat(votes.rosterCount()).isEqualTo(1);
		});
	}

	@Test
	void verdictScoredByADifferentInstrumentIsNotCheckedAgainstThisRoster() {
		SimpleJury original = jury(Judges.alwaysPass("a"));
		SimpleJury replacement = jury(Judges.alwaysPass("a"), Judges.alwaysPass("b"));
		ItemResult keptFromOriginal = scored("a", original, InstrumentRecorder.describe(original));

		ItemAttestation attestation = ItemAttestation.of(keptFromOriginal, InstrumentRecorder.describe(replacement));

		assertThat(attestation.attestability()).isEqualTo(Attestability.VOTES_WITHOUT_ROSTER);
		assertThat(attestation.votes()).singleElement().satisfies(votes -> assertThat(votes.hasRoster()).isFalse());
	}

	@Test
	void metaJuryCountsItsMembersFromTheDescription() {
		Map<String, Object> twoSeats = Map.of("kind", "SIMPLE", "seats", List.of(Map.of(), Map.of()));
		Map<String, Object> description = Map.of("descriptionVersion", 1, "kind", "META", "members",
				List.of(Map.of("name", "first", "jury", twoSeats), Map.of("name", "second", "jury", twoSeats)));
		InstrumentRecord instrument = new InstrumentRecord(1, "meta-hash", description, null, Map.of(), Map.of());
		RecordedVerdict member = verdict(2, List.of(), null);
		RecordedVerdict meta = verdict(2,
				List.of(new RecordedCompositeAttempt("first", "meta_member", null, member, null),
						new RecordedCompositeAttempt("second", "meta_member", null, member, null)),
				"meta-hash");

		ItemAttestation attestation = ItemAttestation.of(item("a").verdict(meta).build(), instrument);

		assertThat(attestation.votes()).extracting(VoteCount::rosterCount).containsExactly(2, 2, 2);
		assertThat(attestation.attestability()).isEqualTo(Attestability.ATTESTED);
	}

	@Test
	void instrumentAndVerdictHashesSurviveTheStoreAndStillAttest(@TempDir Path dir) {
		SimpleJury jury = jury(Judges.alwaysPass("a"), Judges.alwaysPass("b"));
		InstrumentRecord instrument = InstrumentRecorder.describe(jury);
		ExperimentResult result = ExperimentResult.builder()
			.experimentId("run-1")
			.experimentName("roster")
			.datasetSemanticVersion("1.0.0")
			.timestamp(Instant.parse("2026-09-13T00:00:00Z"))
			.items(List.of(scored("a", jury, instrument), item("b").success(false).build()))
			.instrument(instrument)
			.build();
		FileSystemResultStore store = new FileSystemResultStore(dir);
		store.save(result);

		ExperimentResult loaded = store.load("run-1").orElseThrow();
		RunAttestation attestation = RunAttestation.of(loaded);

		assertThat(loaded.instrument()).isNotNull();
		assertThat(loaded.instrument().specHash()).isEqualTo(instrument.specHash());
		assertThat(loaded.instrument().descriptionVersion()).isEqualTo(instrument.descriptionVersion());
		assertThat(loaded.items().get(0).verdict().instrumentHash()).isEqualTo(instrument.specHash());
		assertThat(attestation.count(Attestability.ATTESTED)).isEqualTo(1);
		assertThat(attestation.count(Attestability.NOT_JUDGED)).isEqualTo(1);
	}

	private static ItemResult scored(String id, Jury jury, InstrumentRecord instrument) {
		return item(id).success(true).verdict(RecordedVerdict.from(jury.vote(CONTEXT), instrument.specHash())).build();
	}

	private static ItemResult.Builder item(String id) {
		return ItemResult.builder().itemId(id).itemSlug(id);
	}

	private static SimpleJury jury(Judge... judges) {
		SimpleJury.Builder builder = SimpleJury.builder().votingStrategy(new ConsensusStrategy()).parallel(false);
		for (Judge judge : judges) {
			builder.judge(judge);
		}
		return builder.build();
	}

	private static RecordedVerdict verdict(int inputCount, List<RecordedCompositeAttempt> attempts,
			String instrumentHash) {
		Map<String, Object> evidence = Map.of("aggregation",
				Map.of("inputCount", inputCount, "eligibleCount", inputCount, "errorCount", 0));
		return new RecordedVerdict(
				new RecordedJudgment(RecordedJudgmentStatus.PASS, null, null, "recorded", List.of(), evidence),
				List.of(), Map.of(), Map.of(), attempts, instrumentHash);
	}

}
