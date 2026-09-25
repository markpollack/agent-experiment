package io.github.markpollack.experiment.comparison;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import io.github.markpollack.experiment.judge.JudgeExecutionDetail;
import io.github.markpollack.experiment.recording.CaseInputIdentity;
import io.github.markpollack.experiment.recording.RecordingValidation;

import io.github.markpollack.experiment.result.ExperimentResult;
import io.github.markpollack.experiment.result.ItemResult;
import io.github.markpollack.experiment.store.ResultStore;

/**
 * Default implementation of {@link ComparisonEngine}. Matches items by {@code itemId},
 * computes per-judge score deltas, and classifies each item as
 * IMPROVED/REGRESSED/UNCHANGED/NEW/REMOVED.
 */
public class DefaultComparisonEngine implements ComparisonEngine {

	private final ResultStore resultStore;

	public DefaultComparisonEngine(ResultStore resultStore) {
		this.resultStore = Objects.requireNonNull(resultStore, "resultStore must not be null");
	}

	@Override
	public ComparisonResult compare(ExperimentResult current, ExperimentResult baseline) {
		RecordingValidation.validate(current);
		RecordingValidation.validate(baseline);
		validateJudgeComparison(current, baseline);
		// The conditions mark is load-bearing here or it is decorative everywhere. Two
		// runs
		// permitted different amounts of work did not measure the same thing, and two
		// runs that
		// never said what they were permitted cannot be shown to have. Refusing here is
		// what
		// stops a comparison being drawn across them, which is how a withdrawn headline
		// happened.
		List<String> blockers = current.conditions().incomparabilityWith(baseline.conditions());
		if (!blockers.isEmpty()) {
			throw new IncomparableConditionsException(current.experimentId(), baseline.experimentId(), blockers);
		}

		Map<String, ItemResult> currentByItem = indexById(current);
		Map<String, ItemResult> baselineByItem = indexById(baseline);

		Set<String> allItemIds = new LinkedHashSet<>();
		allItemIds.addAll(currentByItem.keySet());
		allItemIds.addAll(baselineByItem.keySet());

		Set<String> allScorerNames = new LinkedHashSet<>();
		currentByItem.values().forEach(item -> allScorerNames.addAll(item.scores().keySet()));
		baselineByItem.values().forEach(item -> allScorerNames.addAll(item.scores().keySet()));

		List<ItemDiff> itemDiffs = new ArrayList<>();
		for (String itemId : allItemIds) {
			ItemResult cur = currentByItem.get(itemId);
			ItemResult base = baselineByItem.get(itemId);
			ItemDiff diff = computeItemDiff(itemId, cur, base);
			itemDiffs.add(new ItemDiff(diff.itemId(), diff.currentScores(), diff.baselineScores(), diff.scoreDeltas(),
					diff.status(), cur == null ? null : cur.itemId(), base == null ? null : base.itemId()));
		}

		Map<String, ScoreComparison> scoreComparisons = new LinkedHashMap<>();
		for (String scorerName : allScorerNames) {
			scoreComparisons.put(scorerName,
					computeScoreComparison(scorerName, itemDiffs, currentByItem, baselineByItem,
							current.recordingSchemaVersion() != null || baseline.recordingSchemaVersion() != null));
		}

		ExperimentSummary summary = summarize(current);
		return new ComparisonResult(current.experimentId(), baseline.experimentId(), scoreComparisons, itemDiffs,
				summary, current.judgeRun() == null ? null : current.judgeRun().candidateId(),
				baseline.judgeRun() == null ? null : baseline.judgeRun().candidateId(),
				current.judgeRun() == null ? null : current.judgeRun().measureId());
	}

	@Override
	public ExperimentSummary summarize(ExperimentResult experiment) {
		return ExperimentSummary.of(experiment);
	}

	@Override
	public Optional<ExperimentResult> resolveBaseline(ExperimentResult current, Optional<String> explicitBaselineId) {
		// 1. Explicit baseline ID
		if (explicitBaselineId.isPresent()) {
			return resultStore.load(explicitBaselineId.get());
		}

		// 2. "baselineId" in experiment metadata
		String metadataBaselineId = current.metadata().get("baselineId");
		if (metadataBaselineId != null && !metadataBaselineId.isBlank()) {
			return resultStore.load(metadataBaselineId);
		}

		// 3. Most recent prior experiment with same name (excluding self)
		List<ExperimentResult> byName = resultStore.listByName(current.experimentName());
		for (int i = byName.size() - 1; i >= 0; i--) {
			ExperimentResult candidate = byName.get(i);
			if (!candidate.experimentId().equals(current.experimentId())) {
				return Optional.of(candidate);
			}
		}

		return Optional.empty();
	}

	@Override
	public List<ItemDiff> detectRegressions(ComparisonResult comparison, Map<String, Double> thresholds) {
		List<ItemDiff> regressions = new ArrayList<>();
		for (ItemDiff diff : comparison.itemDiffs()) {
			if (diff.status() == DiffStatus.REGRESSED) {
				for (Map.Entry<String, Double> delta : diff.scoreDeltas().entrySet()) {
					Double threshold = thresholds.get(delta.getKey());
					if (threshold != null && delta.getValue() < -threshold) {
						regressions.add(diff);
						break;
					}
				}
			}
		}
		return regressions;
	}

	private ItemDiff computeItemDiff(String itemId, ItemResult current, ItemResult baseline) {
		if (current == null) {
			return new ItemDiff(itemId, Map.of(), baseline.scores(), Map.of(), DiffStatus.REMOVED);
		}
		if (baseline == null) {
			return new ItemDiff(itemId, current.scores(), Map.of(), Map.of(), DiffStatus.NEW);
		}

		Set<String> allScorers = new LinkedHashSet<>();
		allScorers.addAll(current.scores().keySet());
		allScorers.addAll(baseline.scores().keySet());

		Map<String, Double> deltas = new HashMap<>();
		boolean anyImproved = false;
		boolean anyRegressed = false;

		for (String scorer : allScorers) {
			Double curScore = current.scores().get(scorer);
			Double baseScore = baseline.scores().get(scorer);
			if (curScore != null && baseScore != null) {
				double delta = curScore - baseScore;
				deltas.put(scorer, delta);
				if (delta > 0) {
					anyImproved = true;
				}
				else if (delta < 0) {
					anyRegressed = true;
				}
			}
		}

		DiffStatus status;
		if (deltas.isEmpty()) {
			status = DiffStatus.UNSCORED;
		}
		else if (anyRegressed) {
			status = DiffStatus.REGRESSED;
		}
		else if (anyImproved) {
			status = DiffStatus.IMPROVED;
		}
		else {
			status = DiffStatus.UNCHANGED;
		}

		return new ItemDiff(itemId, current.scores(), baseline.scores(), deltas, status);
	}

	private ScoreComparison computeScoreComparison(String scorerName, List<ItemDiff> itemDiffs,
			Map<String, ItemResult> currentByItem, Map<String, ItemResult> baselineByItem, boolean currentFormat) {
		double currentSum = 0;
		int currentCount = 0;
		double baselineSum = 0;
		int baselineCount = 0;
		int improvements = 0;
		int regressions = 0;
		int unchanged = 0;
		int newItems = 0;
		int removedItems = 0;

		for (ItemDiff diff : itemDiffs) {
			if (diff.status() == DiffStatus.NEW) {
				newItems++;
				Double score = diff.currentScores().get(scorerName);
				if (score != null) {
					currentSum += score;
					currentCount++;
				}
				continue;
			}
			if (diff.status() == DiffStatus.REMOVED) {
				removedItems++;
				Double score = diff.baselineScores().get(scorerName);
				if (score != null) {
					baselineSum += score;
					baselineCount++;
				}
				continue;
			}

			Double curScore = diff.currentScores().get(scorerName);
			Double baseScore = diff.baselineScores().get(scorerName);
			if (curScore != null) {
				currentSum += curScore;
				currentCount++;
			}
			if (baseScore != null) {
				baselineSum += baseScore;
				baselineCount++;
			}

			Double delta = diff.scoreDeltas().get(scorerName);
			if (delta != null) {
				if (delta > 0) {
					improvements++;
				}
				else if (delta < 0) {
					regressions++;
				}
				else {
					unchanged++;
				}
			}
		}

		Double currentMean = currentCount > 0 ? Double.valueOf(currentSum / currentCount)
				: currentFormat ? null : Double.valueOf(0);
		Double baselineMean = baselineCount > 0 ? Double.valueOf(baselineSum / baselineCount)
				: currentFormat ? null : Double.valueOf(0);
		Double delta = currentMean == null || baselineMean == null ? null : currentMean - baselineMean;

		return new ScoreComparison(scorerName, currentMean, baselineMean, delta, improvements, regressions, unchanged,
				newItems, removedItems);
	}

	private static void validateJudgeComparison(ExperimentResult current, ExperimentResult baseline) {
		boolean a = "JUDGE".equals(current.experimentKind()), b = "JUDGE".equals(baseline.experimentKind());
		if (!a && !b)
			return;
		List<String> reasons = new ArrayList<>();
		if (!a || !b)
			reasons.add("INCOMPATIBLE: current Judge measures cannot join legacy/subject series");
		else {
			var left = current.judgeRun();
			var right = baseline.judgeRun();
			if (!"COMPLETE".equals(current.runState()) || !"COMPLETE".equals(baseline.runState()))
				reasons.add("INCOMPATIBLE: incomplete runs have only partial diagnostics");
			if (!left.measureId().equals(right.measureId()) || !left.scorerRevision().equals(right.scorerRevision())
					|| !left.truthKind().equals(right.truthKind()) || left.replicateCount() != right.replicateCount())
				reasons.add("INCOMPATIBLE: measure/scorer/truth/replicate declaration differs");
			Map<String, ItemResult> counterparts = indexById(baseline);
			for (var entry : indexById(current).entrySet()) {
				ItemResult other = counterparts.get(entry.getKey());
				if (other == null)
					continue;
				var x = (JudgeExecutionDetail) entry.getValue().executionDetail();
				var y = (JudgeExecutionDetail) other.executionDetail();
				String compatibility = CaseInputIdentity.compatibility(x.caseInputIdentity(), y.caseInputIdentity());
				if (!"MATCH".equals(compatibility))
					reasons.add(compatibility + ": effective input for rows " + current.experimentId() + "/"
							+ entry.getValue().itemId() + " and " + baseline.experimentId() + "/" + other.itemId());
				if (x.truth() == null || y.truth() == null || !x.truth().equals(y.truth()))
					reasons.add("INCOMPATIBLE: supplied truth differs or is unknown for " + entry.getKey());
			}
		}
		if (!reasons.isEmpty())
			throw new IncomparableConditionsException(current.experimentId(), baseline.experimentId(), reasons);
	}

	private static Map<String, ItemResult> indexById(ExperimentResult experiment) {
		Map<String, ItemResult> index = new LinkedHashMap<>();
		for (ItemResult item : experiment.items()) {
			String key = item.itemId();
			if ("JUDGE".equals(experiment.experimentKind())) {
				var row = ((JudgeExecutionDetail) item.executionDetail()).row();
				key = row.caseId().length() + ":" + row.caseId() + ":" + row.replicateIndex();
			}
			if (index.put(key, item) != null)
				throw new IllegalArgumentException("Duplicate comparison counterpart key");
		}
		return index;
	}

}
