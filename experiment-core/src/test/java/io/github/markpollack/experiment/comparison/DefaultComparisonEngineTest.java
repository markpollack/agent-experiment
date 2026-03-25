package io.github.markpollack.experiment.comparison;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.markpollack.experiment.result.ExperimentResult;
import io.github.markpollack.experiment.result.ItemResult;
import io.github.markpollack.experiment.store.ResultStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DefaultComparisonEngineTest {

	private final InMemoryResultStore store = new InMemoryResultStore();

	private final DefaultComparisonEngine engine = new DefaultComparisonEngine(store);

	// --- compare ---

	@Test
	void compareComputesPositiveDeltaForImprovement() {
		ExperimentResult current = experiment("cur", "test",
				List.of(item("ITEM-1", Map.of("build", 1.0, "coverage", 0.8))));
		ExperimentResult baseline = experiment("base", "test",
				List.of(item("ITEM-1", Map.of("build", 0.5, "coverage", 0.6))));

		ComparisonResult result = engine.compare(current, baseline);

		ItemDiff diff = result.itemDiffs().get(0);
		assertThat(diff.status()).isEqualTo(DiffStatus.IMPROVED);
		assertThat(diff.scoreDeltas().get("build")).isEqualTo(0.5);
		assertThat(diff.scoreDeltas().get("coverage")).isCloseTo(0.2, within(0.001));
	}

	@Test
	void compareComputesNegativeDeltaForRegression() {
		ExperimentResult current = experiment("cur", "test", List.of(item("ITEM-1", Map.of("build", 0.0))));
		ExperimentResult baseline = experiment("base", "test", List.of(item("ITEM-1", Map.of("build", 1.0))));

		ComparisonResult result = engine.compare(current, baseline);

		ItemDiff diff = result.itemDiffs().get(0);
		assertThat(diff.status()).isEqualTo(DiffStatus.REGRESSED);
		assertThat(diff.scoreDeltas().get("build")).isEqualTo(-1.0);
	}

	@Test
	void compareDetectsUnchangedScores() {
		ExperimentResult current = experiment("cur", "test", List.of(item("ITEM-1", Map.of("build", 1.0))));
		ExperimentResult baseline = experiment("base", "test", List.of(item("ITEM-1", Map.of("build", 1.0))));

		ComparisonResult result = engine.compare(current, baseline);

		assertThat(result.itemDiffs().get(0).status()).isEqualTo(DiffStatus.UNCHANGED);
	}

	@Test
	void compareMarksNewItems() {
		ExperimentResult current = experiment("cur", "test",
				List.of(item("ITEM-1", Map.of("build", 1.0)), item("ITEM-2", Map.of("build", 0.5))));
		ExperimentResult baseline = experiment("base", "test", List.of(item("ITEM-1", Map.of("build", 1.0))));

		ComparisonResult result = engine.compare(current, baseline);

		ItemDiff newDiff = result.itemDiffs()
			.stream()
			.filter(d -> "ITEM-2".equals(d.itemId()))
			.findFirst()
			.orElseThrow();
		assertThat(newDiff.status()).isEqualTo(DiffStatus.NEW);
		assertThat(newDiff.baselineScores()).isEmpty();
	}

	@Test
	void compareMarksRemovedItems() {
		ExperimentResult current = experiment("cur", "test", List.of(item("ITEM-1", Map.of("build", 1.0))));
		ExperimentResult baseline = experiment("base", "test",
				List.of(item("ITEM-1", Map.of("build", 1.0)), item("ITEM-2", Map.of("build", 0.5))));

		ComparisonResult result = engine.compare(current, baseline);

		ItemDiff removedDiff = result.itemDiffs()
			.stream()
			.filter(d -> "ITEM-2".equals(d.itemId()))
			.findFirst()
			.orElseThrow();
		assertThat(removedDiff.status()).isEqualTo(DiffStatus.REMOVED);
		assertThat(removedDiff.currentScores()).isEmpty();
	}

	@Test
	void compareProducesScoreComparisons() {
		ExperimentResult current = experiment("cur", "test",
				List.of(item("ITEM-1", Map.of("build", 1.0)), item("ITEM-2", Map.of("build", 0.5))));
		ExperimentResult baseline = experiment("base", "test",
				List.of(item("ITEM-1", Map.of("build", 0.5)), item("ITEM-2", Map.of("build", 0.5))));

		ComparisonResult result = engine.compare(current, baseline);

		ScoreComparison buildComparison = result.scoreComparisons().get("build");
		assertThat(buildComparison).isNotNull();
		assertThat(buildComparison.currentMean()).isCloseTo(0.75, within(0.001));
		assertThat(buildComparison.baselineMean()).isCloseTo(0.5, within(0.001));
		assertThat(buildComparison.delta()).isCloseTo(0.25, within(0.001));
		assertThat(buildComparison.improvements()).isEqualTo(1);
		assertThat(buildComparison.unchanged()).isEqualTo(1);
	}

	@Test
	void compareHandlesEmptyExperiments() {
		ExperimentResult current = experiment("cur", "test", List.of());
		ExperimentResult baseline = experiment("base", "test", List.of());

		ComparisonResult result = engine.compare(current, baseline);

		assertThat(result.itemDiffs()).isEmpty();
		assertThat(result.scoreComparisons()).isEmpty();
	}

	@Test
	void compareSingleItem() {
		ExperimentResult current = experiment("cur", "test", List.of(item("ITEM-1", Map.of("build", 1.0))));
		ExperimentResult baseline = experiment("base", "test", List.of(item("ITEM-1", Map.of("build", 1.0))));

		ComparisonResult result = engine.compare(current, baseline);

		assertThat(result.itemDiffs()).hasSize(1);
		assertThat(result.scoreComparisons()).hasSize(1);
	}

	@Test
	void compareMultiJudgeAggregation() {
		ExperimentResult current = experiment("cur", "test",
				List.of(item("ITEM-1", Map.of("build", 1.0, "coverage", 0.9, "migration", 0.7))));
		ExperimentResult baseline = experiment("base", "test",
				List.of(item("ITEM-1", Map.of("build", 1.0, "coverage", 0.5, "migration", 0.8))));

		ComparisonResult result = engine.compare(current, baseline);

		// build unchanged, coverage improved, migration regressed → REGRESSED overall
		// (any regression)
		assertThat(result.itemDiffs().get(0).status()).isEqualTo(DiffStatus.REGRESSED);
		assertThat(result.scoreComparisons()).hasSize(3);
	}

	@Test
	void compareSetsExperimentIds() {
		ExperimentResult current = experiment("cur-123", "test", List.of());
		ExperimentResult baseline = experiment("base-456", "test", List.of());

		ComparisonResult result = engine.compare(current, baseline);

		assertThat(result.currentExperimentId()).isEqualTo("cur-123");
		assertThat(result.baselineExperimentId()).isEqualTo("base-456");
	}

	// --- summarize ---

	@Test
	void summarizeExtractsCorrectAggregates() {
		ExperimentResult experiment = ExperimentResult.builder()
			.experimentId("exp-1")
			.experimentName("my-experiment")
			.datasetDirty(false)
			.datasetSemanticVersion("1.0.0")
			.timestamp(Instant.parse("2026-01-15T10:00:00Z"))
			.items(List.of(item("ITEM-1", Map.of("build", 1.0)), item("ITEM-2", Map.of("build", 0.0))))
			.metadata(Map.of())
			.aggregateScores(Map.of("build", 0.5))
			.passRate(0.5)
			.totalCostUsd(3.50)
			.totalTokens(1000)
			.totalDurationMs(60000)
			.build();

		ExperimentSummary summary = engine.summarize(experiment);

		assertThat(summary.experimentId()).isEqualTo("exp-1");
		assertThat(summary.experimentName()).isEqualTo("my-experiment");
		assertThat(summary.totalItems()).isEqualTo(2);
		assertThat(summary.passRate()).isEqualTo(0.5);
		assertThat(summary.totalCostUsd()).isEqualTo(3.50);
		assertThat(summary.totalTokens()).isEqualTo(1000);
		assertThat(summary.totalDurationMs()).isEqualTo(60000);
		assertThat(summary.scoreAggregates()).containsEntry("build", 0.5);
	}

	// --- resolveBaseline ---

	@Test
	void resolveBaselineUsesExplicitId() {
		ExperimentResult baseline = experiment("base-1", "test", List.of());
		store.save(baseline);

		ExperimentResult current = experiment("cur-1", "test", List.of());

		Optional<ExperimentResult> resolved = engine.resolveBaseline(current, Optional.of("base-1"));

		assertThat(resolved).isPresent();
		assertThat(resolved.get().experimentId()).isEqualTo("base-1");
	}

	@Test
	void resolveBaselineUsesMetadataBaselineId() {
		ExperimentResult baseline = experiment("base-1", "test", List.of());
		store.save(baseline);

		ExperimentResult current = ExperimentResult.builder()
			.experimentId("cur-1")
			.experimentName("test")
			.datasetDirty(false)
			.datasetSemanticVersion("1.0.0")
			.timestamp(Instant.parse("2026-01-15T12:00:00Z"))
			.items(List.of())
			.metadata(Map.of("baselineId", "base-1"))
			.aggregateScores(Map.of())
			.passRate(0.0)
			.build();

		Optional<ExperimentResult> resolved = engine.resolveBaseline(current, Optional.empty());

		assertThat(resolved).isPresent();
		assertThat(resolved.get().experimentId()).isEqualTo("base-1");
	}

	@Test
	void resolveBaselineFallsBackToMostRecentWithSameName() {
		ExperimentResult older = experiment("old-1", "test", "2026-01-15T10:00:00Z", List.of());
		ExperimentResult newer = experiment("new-1", "test", "2026-01-15T11:00:00Z", List.of());
		store.save(older);
		store.save(newer);

		ExperimentResult current = experiment("cur-1", "test", "2026-01-15T12:00:00Z", List.of());
		store.save(current);

		Optional<ExperimentResult> resolved = engine.resolveBaseline(current, Optional.empty());

		assertThat(resolved).isPresent();
		assertThat(resolved.get().experimentId()).isEqualTo("new-1");
	}

	@Test
	void resolveBaselineReturnsEmptyWhenNothingFound() {
		ExperimentResult current = experiment("cur-1", "test", List.of());

		Optional<ExperimentResult> resolved = engine.resolveBaseline(current, Optional.empty());

		assertThat(resolved).isEmpty();
	}

	@Test
	void resolveBaselineExcludesSelf() {
		ExperimentResult current = experiment("cur-1", "test", List.of());
		store.save(current);

		Optional<ExperimentResult> resolved = engine.resolveBaseline(current, Optional.empty());

		assertThat(resolved).isEmpty();
	}

	// --- detectRegressions ---

	@Test
	void detectRegressionsFindsItemsExceedingThreshold() {
		ExperimentResult current = experiment("cur", "test",
				List.of(item("ITEM-1", Map.of("build", 0.0)), item("ITEM-2", Map.of("build", 0.9))));
		ExperimentResult baseline = experiment("base", "test",
				List.of(item("ITEM-1", Map.of("build", 1.0)), item("ITEM-2", Map.of("build", 1.0))));

		ComparisonResult comparison = engine.compare(current, baseline);
		List<ItemDiff> regressions = engine.detectRegressions(comparison, Map.of("build", 0.5));

		// ITEM-1 regressed by 1.0, exceeds threshold 0.5
		// ITEM-2 regressed by 0.1, does not exceed threshold 0.5
		assertThat(regressions).hasSize(1);
		assertThat(regressions.get(0).itemId()).isEqualTo("ITEM-1");
	}

	@Test
	void detectRegressionsReturnsEmptyWhenNoRegressions() {
		ExperimentResult current = experiment("cur", "test", List.of(item("ITEM-1", Map.of("build", 1.0))));
		ExperimentResult baseline = experiment("base", "test", List.of(item("ITEM-1", Map.of("build", 0.5))));

		ComparisonResult comparison = engine.compare(current, baseline);
		List<ItemDiff> regressions = engine.detectRegressions(comparison, Map.of("build", 0.1));

		assertThat(regressions).isEmpty();
	}

	@Test
	void detectRegressionsIgnoresNewAndRemovedItems() {
		ExperimentResult current = experiment("cur", "test", List.of(item("ITEM-NEW", Map.of("build", 0.0))));
		ExperimentResult baseline = experiment("base", "test", List.of(item("ITEM-OLD", Map.of("build", 1.0))));

		ComparisonResult comparison = engine.compare(current, baseline);
		List<ItemDiff> regressions = engine.detectRegressions(comparison, Map.of("build", 0.0));

		assertThat(regressions).isEmpty();
	}

	// --- helpers ---

	private static ExperimentResult experiment(String id, String name, List<ItemResult> items) {
		return experiment(id, name, "2026-01-15T10:00:00Z", items);
	}

	private static ExperimentResult experiment(String id, String name, String timestamp, List<ItemResult> items) {
		return ExperimentResult.builder()
			.experimentId(id)
			.experimentName(name)
			.datasetDirty(false)
			.datasetSemanticVersion("1.0.0")
			.timestamp(Instant.parse(timestamp))
			.items(items)
			.metadata(Map.of())
			.aggregateScores(Map.of())
			.passRate(0.0)
			.build();
	}

	private static ItemResult item(String id, Map<String, Double> scores) {
		return ItemResult.builder()
			.itemId(id)
			.itemSlug(id.toLowerCase())
			.success(true)
			.passed(true)
			.scores(scores)
			.metrics(Map.of())
			.metadata(Map.of())
			.build();
	}

	/**
	 * In-memory ResultStore for testing — avoids filesystem I/O.
	 */
	private static class InMemoryResultStore implements ResultStore {

		private final java.util.Map<String, ExperimentResult> byId = new java.util.LinkedHashMap<>();

		@Override
		public void save(ExperimentResult result) {
			byId.put(result.experimentId(), result);
		}

		@Override
		public Optional<ExperimentResult> load(String id) {
			return Optional.ofNullable(byId.get(id));
		}

		@Override
		public List<ExperimentResult> listByName(String experimentName) {
			return byId.values()
				.stream()
				.filter(r -> r.experimentName().equals(experimentName))
				.sorted(java.util.Comparator.comparing(ExperimentResult::timestamp))
				.toList();
		}

		@Override
		public Optional<ExperimentResult> mostRecent(String experimentName) {
			List<ExperimentResult> byName = listByName(experimentName);
			return byName.isEmpty() ? Optional.empty() : Optional.of(byName.get(byName.size() - 1));
		}

	}

}
