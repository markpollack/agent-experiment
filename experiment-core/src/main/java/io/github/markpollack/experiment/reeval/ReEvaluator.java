package io.github.markpollack.experiment.reeval;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.markpollack.experiment.attestation.InstrumentFailures;
import io.github.markpollack.experiment.attestation.ItemAccounting;
import io.github.markpollack.experiment.result.ExperimentResult;
import io.github.markpollack.experiment.result.InstrumentRecord;
import io.github.markpollack.experiment.result.ItemResult;
import io.github.markpollack.experiment.result.RecordedVerdict;
import io.github.markpollack.experiment.scoring.InstrumentRecorder;
import io.github.markpollack.experiment.scoring.VerdictExtractor;
import io.github.markpollack.experiment.store.ResultStore;
import io.github.markpollack.experiment.scoring.AgentJudgingInput;
import io.github.markpollack.judge.jury.Jury;
import io.github.markpollack.judge.verdict.Verdict;
import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.Nullable;

/**
 * Re-scores stored experiment results with a new Jury. Does NOT re-invoke the original
 * system under test.
 *
 * <p>
 * Delegates context reconstruction to a {@link ReEvaluationContextFactory}, which knows
 * how to rebuild {@link AgentJudgingInput} from stored {@link ItemResult} data for a
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
	public ExperimentResult reEvaluate(ExperimentResult original,
			java.util.function.Function<AgentJudgingInput, Jury> jury) {
		List<ItemResult> items = original.items().stream().map(item -> reEvaluateItem(item, jury)).toList();

		Map<String, Double> aggregateScores = aggregateScores(items);

		ExperimentResult result = ExperimentResult.builder()
			.experimentId(UUID.randomUUID().toString())
			.experimentName(original.experimentName())
			.conditions(original.conditions())
			.datasetVersion(original.datasetVersion())
			.datasetDirty(original.datasetDirty())
			.datasetSemanticVersion(original.datasetSemanticVersion())
			.knowledgeManifest(original.knowledgeManifest())
			.timestamp(Instant.now())
			.items(items)
			.metadata(Map.of("reEvaluatedFrom", original.experimentId(), "systemReinvoked", "false",
					"originalTimestamp", original.timestamp().toString(), "originalCostUsd",
					String.valueOf(original.totalCostUsd()), "originalTokens", String.valueOf(original.totalTokens()),
					"reEvaluationJury", jury.getClass().getSimpleName()))
			.aggregateScores(aggregateScores)
			.counts(ItemAccounting.count(items))
			.totalCostUsd((Double) null)
			.totalTokens((Long) null)
			.totalDurationMs((Long) null)
			.instrument(InstrumentRecorder.common(items))
			.build();

		resultStore.save(result);
		InstrumentFailures.failIfAny(result);
		return result;
	}

	/**
	 * Convenience: load by ID, then re-evaluate.
	 * @param experimentId the ID of the stored experiment to re-evaluate
	 * @param jury the new jury to apply
	 * @return a new experiment result with re-evaluated scores
	 */
	public ExperimentResult reEvaluate(String experimentId, java.util.function.Function<AgentJudgingInput, Jury> jury) {
		ExperimentResult original = resultStore.load(experimentId)
			.orElseThrow(() -> new IllegalArgumentException("No experiment found with id: " + experimentId));
		return reEvaluate(original, jury);
	}

	private ItemResult reEvaluateItem(ItemResult original, java.util.function.Function<AgentJudgingInput, Jury> jury) {
		Optional<AgentJudgingInput> context = contextFactory.create(original);

		if (context.isEmpty()) {
			return preserveAsSkipped(original);
		}

		Jury configuredJury = java.util.Objects.requireNonNull(jury.apply(context.get()));
		InstrumentRecord rowInstrument = InstrumentRecorder.describe(configuredJury);
		Verdict verdict = configuredJury.vote();

		RecordedVerdict recorded = RecordedVerdict.from(verdict, rowInstrument.specHash());
		JsonNode interpretation = io.github.markpollack.experiment.recording.InterpretationSnapshot
			.currentReading(recorded.document());
		ItemResult rescored = original.toBuilder()
			.passed(ItemAccounting.passedFlag(interpretation))
			.scores(VerdictExtractor.extractScores(verdict))
			.verdict(recorded)
			.historical(false)
			.verdictRole("SUBJECT")
			.costUsd((Double) null)
			.totalTokens((Long) null)
			.durationMs((Long) null)
			.interpretation(interpretation)
			.metadata(merge(withoutInstrumentFailure(original.metadata()),
					Map.of("configuredInstrument", rowInstrument, "reEvaluated", "true", "systemReinvoked", "false",
							"originalCostUsd", String.valueOf(original.costUsd()), "reEvaluationJury",
							jury.getClass().getSimpleName())))
			.build();
		return InstrumentFailures.mark(rescored, rowInstrument);
	}

	private ItemResult preserveAsSkipped(ItemResult original) {
		Map<String, Object> provenance = new HashMap<>(original.metadata());
		provenance.put("originalScores", original.scores());
		if (original.executionDetail() instanceof io.github.markpollack.experiment.judge.JudgeExecutionDetail)
			provenance.put("originalJudgeExecutionDetail", original.executionDetail());
		return original.toBuilder()
			.scores(Map.of())
			.executionDetail(
					original.executionDetail() instanceof io.github.markpollack.experiment.judge.JudgeExecutionDetail
							? null : original.executionDetail())
			.verdictRole(original.verdict() == null ? null : "SUBJECT")
			.costUsd((Double) null)
			.totalTokens((Long) null)
			.durationMs((Long) null)
			.metadata(merge(provenance, Map.of("reEvaluated", "false", "reEvaluationSkipped", "true",
					"reEvaluationSkipReason", "missing execution detail or failed original item", "originalCostUsd",
					String.valueOf(original.costUsd()), "originalTokens", String.valueOf(original.totalTokens()))))
			.build();
	}

	/**
	 * Drop the previous verdict's instrument-failure marks.
	 *
	 * <p>
	 * Re-judging is how a run whose jury did not convene is repaired: the agent's work is
	 * kept and a working jury scores it again. Carrying the old marks forward would keep
	 * counting the repaired item as an instrument failure and keep failing the run, so
	 * the repair could never take effect. The marks belong to the verdict being replaced.
	 */
	private static Map<String, Object> withoutInstrumentFailure(Map<String, Object> metadata) {
		Map<String, Object> kept = new HashMap<>(metadata);
		kept.remove(InstrumentRecord.ITEM_INSTRUMENT_FAILURE);
		kept.remove(InstrumentRecord.ITEM_INSTRUMENT_FAILURE_DETAIL);
		return kept;
	}

	private static Map<String, Object> merge(Map<String, Object> base, Map<String, ?> overlay) {
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
