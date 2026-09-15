package io.github.markpollack.experiment.result.history;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.experiment.result.ExperimentResult;
import io.github.markpollack.experiment.result.ItemResult;
import io.github.markpollack.experiment.result.RecordedVerdict;
import io.github.markpollack.experiment.store.FileSystemResultStore;
import io.github.markpollack.judge.Judges;
import io.github.markpollack.judge.context.JudgmentContext;
import io.github.markpollack.judge.jury.ConsensusStrategy;
import io.github.markpollack.judge.jury.SimpleJury;
import io.github.markpollack.judge.jury.Verdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HistoricalResultsTest {

	@Test
	void aVerdictWrittenTodaySurvivesTheWholeRoundTripAndConvertsBack(@TempDir Path dir) throws Exception {
		Verdict live = SimpleJury.builder()
			.judge(Judges.named(Judges.alwaysPass("built"), "build"))
			.judge(Judges.named(Judges.alwaysPass("tidy"), "style"))
			.votingStrategy(new ConsensusStrategy())
			.parallel(false)
			.build()
			.vote(JudgmentContext.builder().goal("round trip").build());
		RecordedVerdict recorded = RecordedVerdict.from(live, "instrument-1");
		FileSystemResultStore store = new FileSystemResultStore(dir);
		store.save(resultWith(recorded));

		HistoricalVerdict historical = only(HistoricalResults.verdictsByItem(resultFile(dir)));

		assertThat(historical.unrecorded("verdict")).isEmpty();
		assertThat(historical.attestable()).isTrue();
		assertThat(historical.seats()).hasSize(2);
		assertThat(historical.seats().get(0).verdictKey()).isEqualTo("build");
		assertThat(historical.decision()).isNotNull();
		assertThat(historical.instrumentHash()).isEqualTo("instrument-1");
		assertThat(historical.individualByName()).containsOnlyKeys("build", "style");
		// Every fact survives producer -> live -> JSON -> historical -> live.
		assertThat(historical.toLive()).isEqualTo(recorded);
	}

	@Test
	void aVerdictStoredBeforeSeatsAndDecisionsKeepsThoseAsUnrecordedAndWillNotConvert() throws Exception {
		String legacy = """
				{"aggregated":{"status":"pass","reasoning":"ok","checks":[],"metadata":{}},
				 "individual":[],"individualByName":{},"weights":{},"compositeAttempts":[]}
				""";

		HistoricalVerdict historical = HistoricalResults.verdict(new ObjectMapper().readTree(legacy));

		assertThat(historical.seats()).isNull();
		assertThat(historical.decision()).isNull();
		assertThat(historical.unrecorded("verdict")).containsExactlyInAnyOrder("verdict.seats", "verdict.decision");
		assertThat(historical.attestable()).isFalse();
		assertThatThrownBy(historical::toLive).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("verdict.seats")
			.hasMessageContaining("verdict.decision");
	}

	@Test
	void anErrorWithoutAReasonCodeIsUnrecordedButAPassWithoutOneIsNot() throws Exception {
		String errored = """
				{"aggregated":{"status":"error","reasoning":"broke","checks":[],"metadata":{}},
				 "individual":[],"individualByName":{},"weights":{},"seats":[],
				 "decision":{"kind":"own"},"compositeAttempts":[]}
				""";
		String passed = errored.replace("\"error\"", "\"pass\"");

		assertThat(HistoricalResults.verdict(new ObjectMapper().readTree(errored)).unrecorded("v"))
			.containsExactly("v.aggregated.reasonCode");
		// A reason code is required only on an ERROR, so its absence on a PASS is not a
		// missing fact and must not make the verdict unattestable.
		assertThat(HistoricalResults.verdict(new ObjectMapper().readTree(passed)).unrecorded("v")).isEmpty();
	}

	@Test
	void anAbsentSeatListIsNotAnEmptyOne() throws Exception {
		String withNone = """
				{"aggregated":{"status":"pass","reasoning":"ok","checks":[],"metadata":{}},
				 "individual":[],"individualByName":{},"weights":{},"seats":[],
				 "decision":{"kind":"own"},"compositeAttempts":[]}
				""";
		String withNothingRecorded = withNone.replace("\"seats\":[],", "");

		assertThat(HistoricalResults.verdict(new ObjectMapper().readTree(withNone)).seats()).isEmpty();
		assertThat(HistoricalResults.verdict(new ObjectMapper().readTree(withNothingRecorded)).seats()).isNull();
	}

	@Test
	void anAttemptWithoutADispositionIsUnrecorded() throws Exception {
		String json = """
				{"aggregated":{"status":"pass","reasoning":"ok","checks":[],"metadata":{}},
				 "individual":[],"individualByName":{},"weights":{},"seats":[],
				 "decision":{"kind":"own"},
				 "compositeAttempts":[{"name":"guardrail","relation":"cascade_tier","failureCode":"jury_execution_failed"}]}
				""";

		assertThat(HistoricalResults.verdict(new ObjectMapper().readTree(json)).unrecorded("v"))
			.containsExactly("v.attempt[guardrail].disposition");
	}

	@Test
	void aStatusThisVersionDoesNotKnowIsAnAbsenceRatherThanACrash() throws Exception {
		String json = """
				{"aggregated":{"status":"invented_later","reasoning":"?","checks":[],"metadata":{}},
				 "individual":[],"individualByName":{},"weights":{},"seats":[],
				 "decision":{"kind":"own"},"compositeAttempts":[]}
				""";

		HistoricalVerdict historical = HistoricalResults.verdict(new ObjectMapper().readTree(json));

		assertThat(historical.aggregated().status()).isNull();
		assertThat(historical.unrecorded("v")).containsExactly("v.aggregated.status");
	}

	@Test
	void aSeatWithoutItsPositionOrKeyIsUnrecordedRatherThanSeatZero() throws Exception {
		String json = """
				{"aggregated":{"status":"pass","reasoning":"ok","checks":[],"metadata":{}},
				 "individual":[],"individualByName":{},"weights":{},
				 "seats":[{"keySource":"positional"}],
				 "decision":{"kind":"own"},"compositeAttempts":[]}
				""";

		HistoricalVerdict historical = HistoricalResults.verdict(new ObjectMapper().readTree(json));

		// Defaulting a missing position to 0 would attach a judgment to a slot nobody
		// wrote down.
		assertThat(historical.seats().get(0).position()).isNull();
		assertThat(historical.unrecorded("v")).containsExactlyInAnyOrder("v.seats[0].position",
				"v.seats[0].verdictKey");
	}

	@Test
	void aCheckWithNoRecordedOutcomeIsUnrecordedRatherThanFailed() throws Exception {
		String json = """
				{"aggregated":{"status":"pass","reasoning":"ok","checks":[{"name":"build","message":"?"}],"metadata":{}},
				 "individual":[],"individualByName":{},"weights":{},"seats":[],
				 "decision":{"kind":"own"},"compositeAttempts":[]}
				""";

		HistoricalVerdict historical = HistoricalResults.verdict(new ObjectMapper().readTree(json));

		assertThat(historical.aggregated().checks().get(0).passed()).isNull();
		assertThat(historical.unrecorded("v")).containsExactly("v.aggregated.checks[0].passed");
	}

	@Test
	void aDecisionThatNamesATierWithoutSayingWhatDecidedItIsIncomplete() throws Exception {
		String json = """
				{"aggregated":{"status":"pass","reasoning":"ok","checks":[],"metadata":{}},
				 "individual":[],"individualByName":{},"weights":{},"seats":[],
				 "decision":{"kind":"tier"},"compositeAttempts":[]}
				""";

		// Present is not the same as complete: a decision that cannot be followed is a
		// required fact missing.
		assertThat(HistoricalResults.verdict(new ObjectMapper().readTree(json)).unrecorded("v"))
			.containsExactlyInAnyOrder("v.decision.tier", "v.decision.basis");
	}

	@Test
	void aFactMissingOnlyInTheNamedMapStillBlocksConversion() throws Exception {
		String json = """
				{"aggregated":{"status":"pass","reasoning":"ok","checks":[],"metadata":{}},
				 "individual":[],
				 "individualByName":{"build":{"status":"error","reasoning":"broke","checks":[],"metadata":{}}},
				 "weights":{},"seats":[],"decision":{"kind":"own"},"compositeAttempts":[]}
				""";

		HistoricalVerdict historical = HistoricalResults.verdict(new ObjectMapper().readTree(json));

		// attestable() must not promise a conversion that toLive() then refuses.
		assertThat(historical.unrecorded("v")).containsExactly("v.individualByName[build].reasonCode");
		assertThat(historical.attestable()).isFalse();
		assertThatThrownBy(historical::toLive).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void aStageMarkedFailedWithoutAReasonIsUnrecorded() throws Exception {
		String json = """
				{"aggregated":{"status":"pass","reasoning":"ok","checks":[],"metadata":{}},
				 "individual":[],"individualByName":{},"weights":{},"seats":[],
				 "decision":{"kind":"own"},
				 "compositeAttempts":[{"name":"guardrail","relation":"cascade_tier","disposition":"stage_failed"}]}
				""";

		// It also recorded neither what the stage returned nor why it returned nothing,
		// which the live type requires exactly one of.
		assertThat(HistoricalResults.verdict(new ObjectMapper().readTree(json)).unrecorded("v"))
			.containsExactlyInAnyOrder("v.attempt[guardrail].dispositionReason", "v.attempt[guardrail].outcome");
	}

	private static HistoricalVerdict only(Map<String, HistoricalVerdict> verdicts) {
		assertThat(verdicts).hasSize(1);
		return verdicts.values().iterator().next();
	}

	private static Path resultFile(Path dir) throws Exception {
		// The store writes an index beside the run, so name the run's own file.
		try (var files = Files.walk(dir)) {
			return files.filter(p -> p.getFileName().toString().equals("run-1.json")).findFirst().orElseThrow();
		}
	}

	private static ExperimentResult resultWith(RecordedVerdict verdict) {
		return ExperimentResult.builder()
			.experimentId("run-1")
			.experimentName("history")
			.datasetSemanticVersion("1.0.0")
			.timestamp(Instant.parse("2026-09-15T00:00:00Z"))
			.items(List
				.of(ItemResult.builder().itemId("a").itemSlug("a").success(true).passed(true).verdict(verdict).build()))
			.build();
	}

}
