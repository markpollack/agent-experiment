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
			itemDiffs.add(computeItemDiff(itemId, cur, base));
		}

		Map<String, ScoreComparison> scoreComparisons = new LinkedHashMap<>();
		for (String scorerName : allScorerNames) {
			scoreComparisons.put(scorerName,
					computeScoreComparison(scorerName, itemDiffs, currentByItem, baselineByItem));
		}

		ExperimentSummary summary = summarize(current);
		return new ComparisonResult(current.experimentId(), baseline.experimentId(), scoreComparisons, itemDiffs,
				summary);
	}

	@Override
	public ExperimentSummary summarize(ExperimentResult experiment) {
		return new ExperimentSummary(experiment.experimentId(), experiment.experimentName(), experiment.items().size(),
				experiment.passRate(), experiment.totalCostUsd(), experiment.totalTokens(),
				experiment.totalDurationMs(), experiment.aggregateScores());
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
		if (anyRegressed) {
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
			Map<String, ItemResult> currentByItem, Map<String, ItemResult> baselineByItem) {
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

		double currentMean = currentCount > 0 ? currentSum / currentCount : 0.0;
		double baselineMean = baselineCount > 0 ? baselineSum / baselineCount : 0.0;
		double delta = currentMean - baselineMean;

		return new ScoreComparison(scorerName, currentMean, baselineMean, delta, improvements, regressions, unchanged,
				newItems, removedItems);
	}

	private static Map<String, ItemResult> indexById(ExperimentResult experiment) {
		Map<String, ItemResult> index = new LinkedHashMap<>();
		for (ItemResult item : experiment.items()) {
			index.put(item.itemId(), item);
		}
		return index;
	}

}
