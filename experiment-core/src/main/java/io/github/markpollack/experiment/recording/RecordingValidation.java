package io.github.markpollack.experiment.recording;

import java.util.*;
import io.github.markpollack.experiment.result.*;
import io.github.markpollack.experiment.judge.*;
import io.github.markpollack.experiment.attestation.ItemAccounting;

/** Current envelope invariants, applied equally before saving and after every read. */
public final class RecordingValidation {

	private RecordingValidation() {
	}

	public static void validate(ExperimentResult result) {
		if (result.recordingSchemaVersion() == null)
			return; // Named historical reader only.
		require(result.recordingSchemaVersion() == 3, "Unsupported Experiment recording version");
		require(List.of("JUDGE", "AGENT").contains(result.experimentKind()), "Unknown experiment kind");
		require(result.writer() != null && result.judgeRuntime() != null, "Recording runtime identity missing");
		RuntimeIdentity current = RuntimeIdentity.judge();
		require(result.judgeRuntime().revision().equals(current.revision())
				&& Objects.equals(result.judgeRuntime().artifacts().get("agent-judge-core"),
						current.artifacts().get("agent-judge-core"))
				&& current.artifacts()
					.entrySet()
					.stream()
					.allMatch(e -> !result.judgeRuntime().artifacts().containsKey(e.getKey())
							|| e.getValue().equals(result.judgeRuntime().artifacts().get(e.getKey()))),
				"Loaded Judge runtime differs from recording runtime");
		require(List.of("COMPLETE", "CANCELLED", "FAILED").contains(result.runState()), "Unknown run state");
		require(result.items().stream().map(ItemResult::itemId).distinct().count() == result.items().size(),
				"Duplicate row/item IDs");
		for (ItemResult item : result.items()) {
			nonnegative(item.totalTokens(), "tokens");
			nonnegative(item.durationMs(), "duration");
			require(item.costUsd() == null || Double.isFinite(item.costUsd()) && item.costUsd() >= 0, "Invalid cost");
			if (item.verdict() != null) {
				require(List.of("SUBJECT", "CANDIDATE").contains(item.verdictRole()), "Verdict role missing");
				require(item.interpretationSnapshot() != null, "VerdictAnalysis snapshot missing");
				item.interpretationSnapshot().validate(item.verdict().document(), current);
				require(item.historical() == "LEGACY_UNVERIFIED".equals(item.interpretationSnapshot().state()),
						"Historical/snapshot state differs");
				if (item.historical())
					require(item.scores().isEmpty(),
							"Copied historical reading cannot enter current comparison metrics");
				require(Objects.equals(item.interpretation(), item.interpretationSnapshot().reading()),
						"Typed reading differs from snapshot");
				if (!item.historical())
					require(Objects.equals(item.passed(), ItemAccounting.passedFlagCurrent(item.interpretation())),
							"Stored passed flag differs from candidate/subject outcome");
			}
			else
				require(item.interpretationSnapshot() == null, "Snapshot without verdict");
		}
		nonnegative(result.totalTokens(), "run tokens");
		nonnegative(result.totalDurationMs(), "run duration");
		require(result.totalCostUsd() == null || Double.isFinite(result.totalCostUsd()) && result.totalCostUsd() >= 0,
				"Invalid run cost");
		if (result.experimentKind().equals("JUDGE"))
			validateJudge(result);
	}

	private static void validateJudge(ExperimentResult result) {
		JudgeRunDeclaration run = Objects.requireNonNull(result.judgeRun(), "Judge run declaration missing");
		require(run.rows().size() == result.items().size(), "Declared population does not match recorded population");
		Map<String, Set<Integer>> replicates = new LinkedHashMap<>();
		for (RowIdentity row : run.rows())
			replicates.computeIfAbsent(row.caseId(), key -> new HashSet<>()).add(row.replicateIndex());
		require(replicates.values().stream().allMatch(indices -> indices.size() == run.replicateCount()),
				"Incomplete replicate declaration");
		List<InvocationObservation> observations = new ArrayList<>();
		Map<String, List<Double>> scores = new LinkedHashMap<>();
		for (int index = 0; index < run.rows().size(); index++) {
			ItemResult item = result.items().get(index);
			require(item.executionDetail() instanceof JudgeExecutionDetail, "Judge row detail missing");
			JudgeExecutionDetail detail = (JudgeExecutionDetail) item.executionDetail();
			require(detail.schemaVersion() == 1 && "JUDGE_EVALUATION".equals(detail.kind()),
					"Unsupported Judge detail");
			RowIdentity row = run.rows().get(index);
			require(row.equals(detail.row()) && row.runId().equals(result.experimentId())
					&& row.rowId().equals(item.itemId()) && row.candidateId().equals(run.candidateId())
					&& row.replicateIndex() < run.replicateCount(), "Row declaration mismatch");
			require(List.of("COMPLETED", "FAILED", "CANCELLED", "NOT_ATTEMPTED").contains(detail.rowState()),
					"Unknown row state");
			require(List.of("RETURNED", "THREW", "NULL_RETURN", "NOT_ATTEMPTED").contains(detail.invocationState()),
					"Unknown invocation state");
			if (result.runState().equals("COMPLETE"))
				require(!List.of("CANCELLED", "NOT_ATTEMPTED").contains(detail.rowState()),
						"Complete run has incomplete row");
			require(item.success() == (detail.candidateJudgment() != null && item.verdict() != null),
					"Success is not a captured return");
			if (detail.candidateJudgment() != null) {
				require("RETURNED".equals(detail.invocationState()) && item.verdict() != null
						&& "CANDIDATE".equals(item.verdictRole()), "Candidate return/role mismatch");
				require(!item.historical(), "Judge candidate must have a verified current snapshot");
				var verdict = item.verdict().document().node();
				var candidate = detail.candidateJudgment().document().node();
				var expectedSingle = ProducerDocument.from("VERDICT",
						io.github.markpollack.judge.verdict.Verdict.single("candidate",
								detail.candidateJudgment()
									.document()
									.value(io.github.markpollack.judge.judgment.Judgment.class)))
					.node();
				require(candidate.equals(verdict.path("judgment")) && expectedSingle.equals(verdict),
						"Candidate and producer single-verdict source differ");
			}
			else
				require(item.verdict() == null, "Verdict without captured candidate");
			JudgeComparison comparison = Objects.requireNonNull(detail.comparison(), "Comparison state missing");
			require(comparison.measureId().equals(run.measureId())
					&& comparison.scorerRevision().equals(run.scorerRevision())
					&& comparison.kind().equals(run.truthKind()), "Comparison/run rule mismatch");
			require(Objects.equals(comparison.truth(), detail.truth()), "Comparison truth mismatch");
			require(Objects.equals(comparison.candidateSha256(),
					detail.candidateJudgment() == null ? null : detail.candidateJudgment().sha256()),
					"Comparison/candidate binding mismatch");
			require(item.scores().equals(comparison.scored() ? Map.of(run.measureId(), comparison.score()) : Map.of()),
					"Public scores differ from comparison");
			if (comparison.scored()) {
				require(detail.candidateJudgment() != null && item.interpretation() != null
						&& item.interpretation().has("accountingVersion"),
						"Scored comparison requires a supported candidate");
				require(comparison.kind().equals("STATUS_LABEL")
						|| List.of(RecordedJudgmentStatus.PASS, RecordedJudgmentStatus.FAIL)
							.contains(detail.candidateJudgment().status()),
						"Ineligible candidate cannot enter semantic comparison");
				scores.computeIfAbsent(run.measureId(), k -> new ArrayList<>()).add(comparison.score());
			}
			if (detail.observation() != null) {
				require(!detail.invocationState().equals("NOT_ATTEMPTED"),
						"Unattempted row has invocation observation");
				require(detail.observation().invocationId().equals(row.runId() + ":" + row.rowId()),
						"Observation/row identity mismatch");
				observations.add(detail.observation());
			}
			else
				require(detail.invocationState().equals("NOT_ATTEMPTED"), "Attempted row has no observation");
			MeasurementSummary rowMeasures = MeasurementSummary
				.of(detail.observation() == null ? List.of() : List.of(detail.observation()));
			require(Objects.equals(item.totalTokens(), rowMeasures.tokens().total())
					&& Objects.equals(item.costUsd(), rowMeasures.costUsd().total()),
					"Row measurement totals disagree with observations");
			Long duration = detail.observation() == null || detail.observation().elapsedNanos() == null ? null
					: detail.observation().elapsedNanos() / 1_000_000;
			require(Objects.equals(item.durationMs(), duration), "Row timing differs from observation");
		}
		MeasurementSummary summary = MeasurementSummary.of(observations);
		require(summary.equals(result.measurements()) && Objects.equals(summary.tokens().total(), result.totalTokens())
				&& Objects.equals(summary.costUsd().total(), result.totalCostUsd()),
				"Run measurement totals disagree with observations");
		require(ItemAccounting.count(result.items()).equals(result.counts()),
				"Run counts disagree with candidate outcomes");
		Map<String, Double> expected = new LinkedHashMap<>();
		scores.forEach((key, values) -> expected.put(key,
				values.stream().mapToDouble(Double::doubleValue).average().orElseThrow()));
		require(expected.equals(result.aggregateScores()), "Run scores disagree with comparisons");
	}

	private static void nonnegative(Long value, String field) {
		require(value == null || value >= 0, "Negative " + field);
	}

	private static void require(boolean condition, String message) {
		if (!condition)
			throw new IllegalArgumentException(message);
	}

}
