package io.github.markpollack.experiment.judge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.markpollack.experiment.comparison.DefaultComparisonEngine;
import io.github.markpollack.experiment.dataset.DatasetItem;
import io.github.markpollack.experiment.result.ExperimentResult;
import io.github.markpollack.experiment.store.InMemoryResultStore;
import io.github.markpollack.judge.Judge;
import io.github.markpollack.experiment.scoring.AgentJudgingInput;
import io.github.markpollack.judge.judgment.Judgment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JudgeExperimentTest {

	private InMemoryResultStore resultStore;

	private List<DatasetItem> testItems;

	@BeforeEach
	void setUp() {
		resultStore = new InMemoryResultStore();
		testItems = List.of(
				new DatasetItem("pos-1", "positive-case", "Summarize report", "judge", "A", false, List.of(), List.of(),
						"active", Path.of("/tmp/test"), null, null),
				new DatasetItem("neg-1", "negative-case", "Summarize report", "judge", "A", false, List.of(), List.of(),
						"active", Path.of("/tmp/test"), null, null));
	}

	@Test
	void resolvesAndInvokesTheSameCandidateOncePerDeclaredRow() {
		var factoryCalls = new AtomicInteger();
		var configuredAtIdentity = new ArrayList<Integer>();
		var invokedCandidates = new ArrayList<Integer>();
		JudgeExperimentResult result = JudgeExperiment.builder()
			.captureMode("FAKE")
			.candidateId("fixture-candidate")
			.caseInputIdentity((context, row) -> {
				configuredAtIdentity.add(factoryCalls.get());
				return new io.github.markpollack.experiment.recording.CaseInputIdentity("fixture", row.caseId(), "1",
						null, "effective-case-input:v1");
			})
			.name("once-per-row")
			.candidate(item -> {
				int configured = factoryCalls.incrementAndGet();
				return () -> {
					invokedCandidates.add(configured);
					return Judgment.pass("Candidate " + configured);
				};
			})
			.items(testItems)
			.replicateCount(3)
			.expected(item -> "PASS")
			.scorer(JudgeScorers.exactVerdictMatch())
			.resultStore(resultStore)
			.build()
			.run();

		assertThat(factoryCalls.get()).isEqualTo(6);
		assertThat(configuredAtIdentity).containsExactly(1, 2, 3, 4, 5, 6);
		assertThat(invokedCandidates).containsExactlyElementsOf(configuredAtIdentity);
		assertThat(result.asExperimentResult().items()).hasSize(6);
		assertThat(result.agreementRate()).isEqualTo(1.0);
	}

	@Test
	void runProducesCorrectAgreementRate() {
		// Judge that always passes
		JudgeExperimentResult result = JudgeExperiment.builder()
			.captureMode("FAKE")
			.candidateId("fixture-candidate")
			.caseInputIdentity((context, row) -> new io.github.markpollack.experiment.recording.CaseInputIdentity(
					"fixture", row.caseId(), "1", null, "effective-case-input:v1"))
			.name("test-judge-experiment")
			.candidate(item -> alwaysPassJudge())
			.items(testItems)
			.expected(item -> "PASS")
			.scorer(JudgeScorers.exactVerdictMatch())
			.resultStore(resultStore)
			.build()
			.run();

		assertThat(result.agreementRate()).isEqualTo(1.0);
		assertThat(result.disagreements()).isEmpty();
	}

	@Test
	void disagreementsPopulatedForMismatches() {
		// Judge that always passes, but expected is FAIL for second item
		JudgeExperimentResult result = JudgeExperiment.builder()
			.captureMode("FAKE")
			.candidateId("fixture-candidate")
			.caseInputIdentity((context, row) -> new io.github.markpollack.experiment.recording.CaseInputIdentity(
					"fixture", row.caseId(), "1", null, "effective-case-input:v1"))
			.name("mismatch-experiment")
			.candidate(item -> alwaysPassJudge())
			.items(testItems)
			.expected(item -> item.id().equals("pos-1") ? "PASS" : "FAIL")
			.scorer(JudgeScorers.exactVerdictMatch())
			.resultStore(resultStore)
			.build()
			.run();

		assertThat(result.agreementRate()).isEqualTo(0.5);
		assertThat(result.disagreements()).hasSize(1);
		assertThat(result.disagreements().get(0).detail().row().caseId()).isEqualTo("neg-1");
	}

	@Test
	void metadataContainsJudgeExperimentType() {
		JudgeExperimentResult result = runDefaultExperiment();

		ExperimentResult raw = result.asExperimentResult();
		assertThat(raw.metadata()).containsEntry("experimentType", "judge");
		assertThat(raw.metadata()).containsKey("candidateJudge");
		assertThat(raw.metadata()).containsKey("scorer");
	}

	@Test
	void judgeExecutionDetailStoredInEachItem() {
		JudgeExperimentResult result = runDefaultExperiment();

		for (var item : result.asExperimentResult().items()) {
			assertThat(item.executionDetail()).isInstanceOf(JudgeExecutionDetail.class);
			JudgeExecutionDetail detail = (JudgeExecutionDetail) item.executionDetail();
			assertThat(detail.candidateJudgment()).isNotNull();
			assertThat(detail.expectedLabel()).isNotNull();
			assertThat(detail.scorerResult()).isNotNull();
		}
	}

	@Test
	void resultPersistsViaResultStore() {
		JudgeExperimentResult result = runDefaultExperiment();

		assertThat(resultStore.size()).isEqualTo(1);
		assertThat(resultStore.load(result.asExperimentResult().experimentId())).isPresent();
	}

	@Test
	void comparisonEngineWorksAcrossTwoJudgeResults() {
		JudgeExperimentResult v1 = JudgeExperiment.builder()
			.captureMode("FAKE")
			.candidateId("fixture-candidate")
			.caseInputIdentity((context, row) -> new io.github.markpollack.experiment.recording.CaseInputIdentity(
					"fixture", row.caseId(), "1", null, "effective-case-input:v1"))
			.name("compare-experiment")
			.candidate(item -> alwaysPassJudge())
			.items(testItems)
			.expected(item -> "PASS")
			.scorer(JudgeScorers.exactVerdictMatch())
			.resultStore(resultStore)
			.build()
			.run();

		JudgeExperimentResult v2 = JudgeExperiment.builder()
			.captureMode("FAKE")
			.candidateId("fixture-candidate")
			.caseInputIdentity((context, row) -> new io.github.markpollack.experiment.recording.CaseInputIdentity(
					"fixture", row.caseId(), "1", null, "effective-case-input:v1"))
			.name("compare-experiment")
			.candidate(item -> alwaysFailJudge())
			.items(testItems)
			.expected(item -> "PASS")
			.scorer(JudgeScorers.exactVerdictMatch())
			.resultStore(resultStore)
			.build()
			.run();

		var engine = new DefaultComparisonEngine(resultStore);
		var comparison = engine.compare(v2.asExperimentResult(), v1.asExperimentResult());

		assertThat(comparison).isNotNull();
		assertThat(comparison.currentExperimentId()).isEqualTo(v2.asExperimentResult().experimentId());
		assertThat(comparison.baselineExperimentId()).isEqualTo(v1.asExperimentResult().experimentId());
	}

	// --- Helpers ---

	private JudgeExperimentResult runDefaultExperiment() {
		return JudgeExperiment.builder()
			.captureMode("FAKE")
			.candidateId("fixture-candidate")
			.caseInputIdentity((context, row) -> new io.github.markpollack.experiment.recording.CaseInputIdentity(
					"fixture", row.caseId(), "1", null, "effective-case-input:v1"))
			.name("default-experiment")
			.candidate(item -> alwaysPassJudge())
			.items(testItems)
			.expected(item -> "PASS")
			.scorer(JudgeScorers.exactVerdictMatch())
			.resultStore(resultStore)
			.build()
			.run();
	}

	private static Judge alwaysPassJudge() {
		return () -> Judgment.pass("Passed");
	}

	private static Judge alwaysFailJudge() {
		return () -> Judgment.fail("Failed");
	}

}
