package io.github.markpollack.experiment.reeval;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.markpollack.experiment.result.ExperimentResult;
import io.github.markpollack.experiment.result.ItemResult;
import io.github.markpollack.experiment.scoring.VerdictExtractor;
import io.github.markpollack.experiment.store.ResultStore;
import io.github.markpollack.judge.context.JudgmentContext;
import io.github.markpollack.judge.jury.Jury;
import io.github.markpollack.judge.jury.Verdict;

/**
 * Re-scores stored experiment results with a new Jury. Does NOT re-invoke the original
 * system under test.
 *
 * <p>
 * Delegates context reconstruction to a {@link ReEvaluationContextFactory}, which knows
 * how to rebuild {@link JudgmentContext} from stored {@link ItemResult} data for a
 * specific experiment type.
 */
public final class ReEvaluator {

	private final ResultStore resultStore;

	private final ReEvaluationContextFactory contextFactory;

	private ReEvaluator(ResultStore resultStore, ReEvaluationContextFactory contextFactory) {
		this.resultStore = java.util.Objects.requireNonNull(resultStore, "resultStore must not be null");
		this.contextFactory = java.util.Objects.requireNonNull(contextFactory, "contextFactory must not be null");
	}

	public static Builder builder() {
		return new Builder();
	}

	/** Convenience: default agent context reconstruction. */
	public static ReEvaluator agentDefaults(ResultStore resultStore) {
		return new ReEvaluator(resultStore, AgentReEvaluationContextFactory.defaults());
	}

	/**
	 * Re-evaluate a stored experiment result with a new jury.
	 * @param original the original experiment result
	 * @param jury the new jury to apply
	 * @return a new experiment result with re-evaluated scores
	 */
	public ExperimentResult reEvaluate(ExperimentResult original, Jury jury) {
		List<ItemResult> items = original.items().stream().map(item -> reEvaluateItem(item, jury)).toList();

		double passRate = items.isEmpty() ? 0.0
				: (double) items.stream().filter(ItemResult::passed).count() / items.size();
		Map<String, Double> aggregateScores = aggregateScores(items);

		ExperimentResult result = ExperimentResult.builder()
			.experimentId(UUID.randomUUID().toString())
			.experimentName(original.experimentName())
			.datasetVersion(original.datasetVersion())
			.datasetDirty(original.datasetDirty())
			.datasetSemanticVersion(original.datasetSemanticVersion())
			.knowledgeManifest(original.knowledgeManifest())
			.timestamp(Instant.now())
			.items(items)
			.metadata(
					Map.of("reEvaluatedFrom", original.experimentId(), "systemReinvoked", "false", "originalTimestamp",
							original.timestamp().toString(), "reEvaluationJury", jury.getClass().getSimpleName()))
			.aggregateScores(aggregateScores)
			.passRate(passRate)
			.totalCostUsd(original.totalCostUsd())
			.totalTokens(original.totalTokens())
			.totalDurationMs(0)
			.build();

		resultStore.save(result);
		return result;
	}

	/**
	 * Convenience: load by ID, then re-evaluate.
	 * @param experimentId the ID of the stored experiment to re-evaluate
	 * @param jury the new jury to apply
	 * @return a new experiment result with re-evaluated scores
	 */
	public ExperimentResult reEvaluate(String experimentId, Jury jury) {
		ExperimentResult original = resultStore.load(experimentId)
			.orElseThrow(() -> new IllegalArgumentException("No experiment found with id: " + experimentId));
		return reEvaluate(original, jury);
	}

	private ItemResult reEvaluateItem(ItemResult original, Jury jury) {
		Optional<JudgmentContext> context = contextFactory.create(original);

		if (context.isEmpty()) {
			return preserveAsSkipped(original);
		}

		Verdict verdict = jury.vote(context.get());

		return original.toBuilder()
			.passed(VerdictExtractor.passed(verdict))
			.scores(VerdictExtractor.extractScores(verdict))
			.verdict(verdict)
			.metadata(merge(original.metadata(),
					Map.of("reEvaluated", "true", "systemReinvoked", "false", "originalCostUsd",
							String.valueOf(original.costUsd()), "reEvaluationJury", jury.getClass().getSimpleName())))
			.build();
	}

	private ItemResult preserveAsSkipped(ItemResult original) {
		return original.toBuilder()
			.metadata(merge(original.metadata(),
					Map.of("reEvaluated", "false", "reEvaluationSkipped", "true", "reEvaluationSkipReason",
							"missing execution detail or failed original item")))
			.build();
	}

	private static Map<String, Object> merge(Map<String, Object> base, Map<String, String> overlay) {
		Map<String, Object> merged = new HashMap<>(base);
		merged.putAll(overlay);
		return merged;
	}

	private static Map<String, Double> aggregateScores(List<ItemResult> items) {
		if (items.isEmpty()) {
			return Map.of();
		}
		java.util.Set<String> scoreNames = new java.util.LinkedHashSet<>();
		for (ItemResult item : items) {
			scoreNames.addAll(item.scores().keySet());
		}
		Map<String, Double> aggregates = new java.util.LinkedHashMap<>();
		for (String name : scoreNames) {
			double sum = 0;
			int count = 0;
			for (ItemResult item : items) {
				Double score = item.scores().get(name);
				if (score != null) {
					sum += score;
					count++;
				}
			}
			if (count > 0) {
				aggregates.put(name, sum / count);
			}
		}
		return Map.copyOf(aggregates);
	}

	public static final class Builder {

		private ResultStore resultStore;

		private ReEvaluationContextFactory contextFactory;

		private Builder() {
		}

		public Builder resultStore(ResultStore resultStore) {
			this.resultStore = resultStore;
			return this;
		}

		public Builder contextFactory(ReEvaluationContextFactory contextFactory) {
			this.contextFactory = contextFactory;
			return this;
		}

		public ReEvaluator build() {
			return new ReEvaluator(resultStore, contextFactory);
		}

	}

}
