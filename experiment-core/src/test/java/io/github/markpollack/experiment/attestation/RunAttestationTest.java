package io.github.markpollack.experiment.attestation;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.markpollack.experiment.result.ExperimentResult;
import io.github.markpollack.experiment.result.ItemResult;
import io.github.markpollack.experiment.result.RecordedCompositeAttempt;
import io.github.markpollack.experiment.result.RecordedJudgment;
import io.github.markpollack.experiment.result.RecordedJudgmentStatus;
import io.github.markpollack.experiment.result.RecordedVerdict;
import io.github.markpollack.experiment.store.FileSystemResultStore;
import io.github.markpollack.judge.Judge;
import io.github.markpollack.judge.Judges;
import io.github.markpollack.judge.context.JudgmentContext;
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

class RunAttestationTest {

	private static final JudgmentContext CONTEXT = JudgmentContext.builder().goal("attestation").build();

	private static final Judge THROWS = context -> {
		throw new IllegalStateException("judge unavailable");
	};

	@Test
	void itemThatNeverReachedAJuryIsNotJudged() {
		ItemAttestation attestation = ItemAttestation.of(item("a").success(false).build());

		assertThat(attestation.attestability()).isEqualTo(Attestability.NOT_JUDGED);
		assertThat(attestation.outcome()).isNull();
		assertThat(attestation.votes()).isEmpty();
	}

	@Test
	void simpleJuryVotesAreReadFromItsEvidence() {
		Verdict verdict = SimpleJury.builder()
			.judge(Judges.alwaysPass("fine"))
			.judge(THROWS)
			.votingStrategy(new ConsensusStrategy())
			.parallel(false)
			.build()
			.vote(CONTEXT);

		ItemAttestation attestation = ItemAttestation.of(item("a").verdict(verdict).build());

		assertThat(attestation.attestability()).isEqualTo(Attestability.VOTES_WITHOUT_ROSTER);
		assertThat(attestation.outcome()).isEqualTo(RecordedJudgmentStatus.ERROR);
		assertThat(attestation.votes()).singleElement().satisfies(votes -> {
			assertThat(votes.scope()).isEqualTo("jury");
			assertThat(votes.inputCount()).isEqualTo(2);
			assertThat(votes.errorCount()).isEqualTo(1);
			assertThat(votes.eligibleCount()).isEqualTo(0);
			assertThat(votes.errorPolicy()).isEqualTo("propagate");
		});
	}

	@Test
	void cascadeReadsEachEnteredTierOnceAndNotItsCopiedAggregate() {
		Verdict verdict = CascadedJury.builder()
			.tier("guardrail", jury(Judges.alwaysPass("built")), TierPolicy.REJECT_ON_ANY_FAIL)
			.tier("quality", jury(Judges.alwaysPass("good"), Judges.alwaysPass("tidy")), TierPolicy.FINAL_TIER)
			.build()
			.vote(CONTEXT);

		ItemAttestation attestation = ItemAttestation.of(item("a").verdict(verdict).build());

		assertThat(attestation.attestability()).isEqualTo(Attestability.VOTES_WITHOUT_ROSTER);
		assertThat(attestation.votes()).extracting(VoteCount::scope).containsExactly("guardrail", "quality");
		assertThat(attestation.votes()).extracting(VoteCount::inputCount).containsExactly(1, 2);
	}

	@Test
	void tierThatFailedToExecuteLeavesTheDenominatorUnattested() {
		Verdict verdict = CascadedJury.builder()
			.tier("guardrail", failingJury(), TierPolicy.REJECT_ON_ANY_FAIL)
			.tier("quality", jury(Judges.alwaysPass("good")), TierPolicy.FINAL_TIER)
			.build()
			.vote(CONTEXT);

		ItemAttestation attestation = ItemAttestation.of(item("a").verdict(verdict).build());

		assertThat(attestation.attestability()).isEqualTo(Attestability.NO_VOTE_EVIDENCE);
		assertThat(attestation.votes()).first().satisfies(votes -> {
			assertThat(votes.scope()).isEqualTo("guardrail");
			assertThat(votes.outcome()).isNull();
			assertThat(votes.failureCode()).isNotNull();
			assertThat(votes.hasEvidence()).isFalse();
		});
	}

	@Test
	void verdictWithoutEvidenceRecordsCountsAsAbsentNotZero() {
		RecordedVerdict legacy = new RecordedVerdict(judgment(RecordedJudgmentStatus.PASS, Map.of()), List.of(),
				Map.of(), Map.of(), List.of());

		ItemAttestation attestation = ItemAttestation.of(item("a").verdict(legacy).build());

		assertThat(attestation.attestability()).isEqualTo(Attestability.NO_VOTE_EVIDENCE);
		assertThat(attestation.votes()).singleElement().satisfies(votes -> {
			assertThat(votes.inputCount()).isNull();
			assertThat(votes.errorCount()).isNull();
		});
	}

	@Test
	void metaJuryAggregateIsItsOwnReductionAndIsReadAlongsideItsMembers() {
		Map<String, Object> evidence = Map.of("aggregation",
				Map.of("strategy", "majority", "inputCount", 2, "eligibleCount", 2, "errorCount", 0));
		RecordedVerdict member = new RecordedVerdict(judgment(RecordedJudgmentStatus.PASS, evidence), List.of(),
				Map.of(), Map.of(), List.of());
		RecordedVerdict meta = new RecordedVerdict(judgment(RecordedJudgmentStatus.PASS, evidence), List.of(), Map.of(),
				Map.of(), List.of(new RecordedCompositeAttempt("first", "meta_member", null, member, null),
						new RecordedCompositeAttempt("second", "meta_member", null, member, null)));

		ItemAttestation attestation = ItemAttestation.of(item("a").verdict(meta).build());

		assertThat(attestation.votes()).extracting(VoteCount::scope).containsExactly("jury", "first", "second");
		assertThat(attestation.attestability()).isEqualTo(Attestability.VOTES_WITHOUT_ROSTER);
	}

	@Test
	void runCountsAreTakenFromWhatWasStoredOnDisk(@TempDir Path dir) {
		Verdict errored = jury(Judges.alwaysPass("fine"), THROWS).vote(CONTEXT);
		Verdict passed = jury(Judges.alwaysPass("fine")).vote(CONTEXT);
		ExperimentResult result = ExperimentResult.builder()
			.experimentId("run-1")
			.experimentName("attestation")
			.datasetSemanticVersion("1.0.0")
			.timestamp(Instant.parse("2026-09-13T00:00:00Z"))
			.items(List.of(item("a").success(true).verdict(errored).build(),
					item("b").success(true).passed(true).verdict(passed).build(), item("c").success(false).build()))
			.build();
		FileSystemResultStore store = new FileSystemResultStore(dir);
		store.save(result);

		RunAttestation attestation = RunAttestation.of(store.load("run-1").orElseThrow());

		assertThat(attestation.count(RecordedJudgmentStatus.PASS)).isEqualTo(1);
		assertThat(attestation.count(RecordedJudgmentStatus.ERROR)).isEqualTo(1);
		assertThat(attestation.count((RecordedJudgmentStatus) null)).isEqualTo(1);
		assertThat(attestation.count(Attestability.VOTES_WITHOUT_ROSTER)).isEqualTo(2);
		assertThat(attestation.count(Attestability.NOT_JUDGED)).isEqualTo(1);
		assertThat(attestation.items().get(0).votes().get(0).inputCount()).isEqualTo(2);
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

	private static Jury failingJury() {
		return new Jury() {
			@Override
			public List<Judge> getJudges() {
				return List.of(Judges.alwaysPass("never asked"));
			}

			@Override
			public VotingStrategy getVotingStrategy() {
				return new ConsensusStrategy();
			}

			@Override
			public Verdict vote(JudgmentContext context) {
				throw new IllegalStateException("tier unavailable");
			}
		};
	}

	private static RecordedJudgment judgment(RecordedJudgmentStatus status, Map<String, Object> metadata) {
		return new RecordedJudgment(status, null, null, "recorded", List.of(), metadata);
	}

}
