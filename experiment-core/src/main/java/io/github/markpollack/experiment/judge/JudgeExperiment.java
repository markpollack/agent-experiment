package io.github.markpollack.experiment.judge;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.function.BiFunction;
import io.github.markpollack.experiment.dataset.DatasetItem;
import io.github.markpollack.experiment.result.*;
import io.github.markpollack.experiment.recording.*;
import io.github.markpollack.experiment.store.ResultStore;
import io.github.markpollack.experiment.attestation.ItemAccounting;
import io.github.markpollack.judge.Judge;
import io.github.markpollack.judge.context.JudgmentContext;
import io.github.markpollack.judge.result.Judgment;
import io.github.markpollack.judge.jury.Verdict;
import io.github.markpollack.judge.jury.interpretation.ReadingSupport;
import org.jspecify.annotations.Nullable;

/**
 * Runs declared case/replicate observations, retaining candidates before any comparison.
 */
public final class JudgeExperiment {

	private final Builder config;

	private JudgeExperiment(Builder builder) {
		Objects.requireNonNull(builder.name, "name");
		Objects.requireNonNull(builder.candidate, "candidate");
		Objects.requireNonNull(builder.items, "items");
		Objects.requireNonNull(builder.input, "input");
		Objects.requireNonNull(builder.scorer, "scorer");
		Objects.requireNonNull(builder.resultStore, "resultStore");
		if (builder.truth == null && builder.expected == null)
			throw new IllegalArgumentException("Truth supplier required");
		builder.items = List.copyOf(builder.items);
		if (builder.items.stream().map(DatasetItem::id).distinct().count() != builder.items.size())
			throw new IllegalArgumentException("Case IDs must be unique");
		if (builder.replicateCount < 1)
			throw new IllegalArgumentException("replicateCount must be positive");
		if (builder.measureId == null)
			builder.measureId = builder.scorer.measureId();
		if (builder.scorerRevision == null)
			builder.scorerRevision = builder.scorer.scorerRevision();
		if (builder.truthKind == null)
			builder.truthKind = builder.scorer.truthKind();
		if (builder.measureId == null || builder.measureId.isBlank() || builder.scorerRevision == null
				|| builder.scorerRevision.isBlank())
			throw new IllegalArgumentException("Custom scorer needs measureId/scorerRevision");
		if (builder.candidateId == null || builder.candidateId.isBlank())
			throw new IllegalArgumentException("Explicit candidate/configuration ID required");
		if (builder.captureMode == null
				|| !List.of("FAKE", "FIXTURE", "RETAINED", "LIVE").contains(builder.captureMode))
			throw new IllegalArgumentException("Explicit captureMode required");
		this.config = builder.copy();
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Execute ordinary failures independently; save one complete or explicitly partial
	 * population.
	 */
	public JudgeExperimentResult run() {
		String runId = config.runId == null ? UUID.randomUUID().toString() : config.runId;
		Instant start = Instant.now();
		long nanos = System.nanoTime();
		List<RowIdentity> declaration = new ArrayList<>();
		for (DatasetItem item : config.items)
			for (int replicate = 0; replicate < config.replicateCount; replicate++)
				declaration.add(RowIdentity.of(runId, item.id(), config.candidateId, replicate));
		JudgeRunDeclaration declared = new JudgeRunDeclaration(config.candidateId, config.measureId,
				config.scorerRevision, config.truthKind, config.replicateCount, "scored-comparisons:v1",
				"candidate-outcome:v1", declaration);
		List<ItemResult> results = new ArrayList<>();
		boolean cancelled = false;
		try {
			for (int index = 0; index < declaration.size(); index++) {
				RowIdentity row = declaration.get(index);
				DatasetItem item = config.items.get(index / config.replicateCount);
				if (cancelled || Thread.currentThread().isInterrupted()) {
					cancelled = true;
					results.add(unattempted(item, row));
					continue;
				}
				ItemResult result = runItem(item, row);
				results.add(result);
				if (((JudgeExecutionDetail) result.executionDetail()).rowState().equals("CANCELLED")
						|| Thread.currentThread().isInterrupted())
					cancelled = true;
			}
			// Clear only while committing the already captured cancellation record;
			// restore below.
			cancelled = Thread.interrupted() || cancelled;
			List<InvocationObservation> observations = results.stream()
				.map(v -> ((JudgeExecutionDetail) v.executionDetail()).observation())
				.filter(Objects::nonNull)
				.toList();
			MeasurementSummary measurements = MeasurementSummary.of(observations);
			ExperimentResult result = ExperimentResult.builder()
				.experimentId(runId)
				.experimentName(config.name)
				.experimentKind("JUDGE")
				.judgeRun(declared)
				.runState(cancelled ? "CANCELLED" : "COMPLETE")
				.datasetSemanticVersion(config.datasetVersion)
				.timestamp(start)
				.items(results)
				.metadata(Map.of("experimentType", "judge", "candidateId", config.candidateId, "candidateJudge",
						config.candidate.getClass().getName(), "scorer", config.scorer.getClass().getName(),
						"comparisonRule", "scored-comparisons:v1", "countRule", "candidate-outcome:v1"))
				.counts(ItemAccounting.count(results))
				.aggregateScores(aggregate(results))
				.measurements(measurements)
				.totalCostUsd(measurements.costUsd().total())
				.totalTokens(measurements.tokens().total())
				.totalDurationMs((System.nanoTime() - nanos) / 1_000_000)
				.build();
			RecordingValidation.validate(result);
			config.resultStore.save(result);
			return JudgeExperimentResult.from(result);
		}
		finally {
			if (cancelled)
				Thread.currentThread().interrupt();
		}
	}

	private ItemResult unattempted(DatasetItem item, RowIdentity row) {
		JudgeComparison comparison = unscored(null, null, "NOT_ATTEMPTED", "Declared row was not invoked");
		return ItemResult.builder()
			.itemId(row.rowId())
			.itemSlug(item.slug())
			.executionDetail(JudgeExecutionDetail.current(row, null, "NOT_ATTEMPTED", "NOT_ATTEMPTED", null, null,
					comparison, null, List.of()))
			.build();
	}

	private ItemResult runItem(DatasetItem item, RowIdentity row) {
		Judgment actual = null;
		RecordedJudgment captured = null;
		RecordedVerdict verdict = null;
		CaseInputIdentity identity = null;
		JudgeTruth truth = null;
		InvocationObservation observation = null;
		String invocation = "NOT_ATTEMPTED", rowState = "COMPLETED", phase = "INPUT";
		List<FailureFact> failures = new ArrayList<>();
		JudgeComparison comparison = null;
		try {
			JudgmentContext context = Objects.requireNonNull(config.input.apply(item), "Input supplier returned null");
			if (config.identity != null)
				identity = config.identity.apply(context, row);
			if (Thread.currentThread().isInterrupted())
				throw new java.util.concurrent.CancellationException("Interrupted before invocation");
			phase = "INVOCATION";
			Instant started = Instant.now();
			long before = System.nanoTime();
			try {
				actual = config.candidate.judge(context);
				invocation = actual == null ? "NULL_RETURN" : "RETURNED";
			}
			catch (RuntimeException error) {
				invocation = "THREW";
				if (error instanceof java.util.concurrent.CancellationException)
					Thread.currentThread().interrupt();
				failures.add(FailureFact.of("INVOCATION", error));
				rowState = "FAILED";
				comparison = unscored(null, null, "INVOCATION_FAILED", "Candidate threw before returning a Judgment");
			}
			finally {
				observation = new InvocationObservation(row.runId() + ":" + row.rowId(), config.captureMode,
						config.captureMode.equals("RETAINED") ? null : started,
						config.captureMode.equals("RETAINED") ? null : Instant.now(),
						config.captureMode.equals("RETAINED") ? null : Math.max(0, System.nanoTime() - before), null,
						null, null, List.of(), List.of());
			}
			if (actual == null && invocation.equals("NULL_RETURN")) {
				failures.add(FailureFact.of(phase, "NULL_RETURN", "Candidate returned null"));
				rowState = "FAILED";
			}
			else if (actual != null) {
				phase = "RECORDING";
				captured = config.recorder.apply(actual);
				if (captured == null)
					throw new IllegalStateException("Recorder returned no source");
				verdict = RecordedVerdict.from(Verdict.single("candidate", actual));
			}
			phase = "OBSERVATION";
			InvocationObservation basic = observation;
			try {
				if (captured != null)
					observation = JevObservations.extract(captured, observation);
				if (config.observationExtractor != null)
					observation = Objects.requireNonNull(
							config.observationExtractor.extract(row, captured, List.copyOf(failures), observation),
							"Observation extractor returned null");
				if (!observation.invocationId().equals(row.runId() + ":" + row.rowId()))
					throw new IllegalArgumentException("Observation belongs to another invocation");
			}
			catch (RuntimeException error) {
				failures.add(FailureFact.of("OBSERVATION", error));
				if (error instanceof java.util.concurrent.CancellationException)
					Thread.currentThread().interrupt();
				observation = new InvocationObservation(row.runId() + ":" + row.rowId(), config.captureMode,
						basic.startedAt(), basic.endedAt(), basic.elapsedNanos(), null, null, null, List.of(),
						List.of());
			}
			if (Thread.currentThread().isInterrupted())
				throw new java.util.concurrent.CancellationException("Interrupted after invocation");
			if (captured != null) {
				phase = "TRUTH";
				truth = config.truth != null ? config.truth.apply(item) : legacyTruth(config.expected.apply(item));
				if (Thread.currentThread().isInterrupted())
					throw new java.util.concurrent.CancellationException("Interrupted during truth lookup");
				String invalid = invalidTruth(truth);
				if (invalid != null)
					comparison = unscored(captured, truth, invalid, "Supplied truth is absent or incompatible");
				else {
					phase = "SCORING";
					var reading = io.github.markpollack.judge.jury.interpretation.Verdicts
						.interpret(verdict.document().producerMap());
					if (reading.readingSupport() != ReadingSupport.SUPPORTED)
						comparison = unscored(captured, truth, "UNSUPPORTED_CANDIDATE",
								"Candidate has no supported reading");
					else {
						JudgeScorerResult score = Objects.requireNonNull(
								config.scorer.score(new JudgeScoringInput(item, actual, truth.label())),
								"Scorer returned null");
						if (Thread.currentThread().isInterrupted())
							throw new java.util.concurrent.CancellationException("Interrupted during scoring");
						comparison = score.scored()
								? new JudgeComparison(config.measureId, config.scorerRevision, config.truthKind,
										captured.sha256(), truth, true, score.match(), score.score(), null,
										score.reasoning())
								: unscored(captured, truth, unscoredCandidateReason(actual), score.reasoning());
					}
				}
			}
		}
		catch (RuntimeException failure) {
			boolean cancelled = Thread.currentThread().isInterrupted()
					|| failure instanceof java.util.concurrent.CancellationException;
			rowState = cancelled ? "CANCELLED" : "FAILED";
			failures.add(FailureFact.of(phase, failure));
			comparison = unscored(captured, truth, cancelled ? "CANCELLED" : phase + "_FAILED",
					failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
		}
		if (comparison == null)
			comparison = unscored(captured, truth, invocation.equals("NULL_RETURN") ? "NULL_RETURN" : "NO_CANDIDATE",
					"No captured candidate available for comparison");
		if (!failures.isEmpty() && !rowState.equals("CANCELLED"))
			rowState = "FAILED";
		var detail = JudgeExecutionDetail.current(row, identity, rowState, invocation, captured, truth, comparison,
				observation, failures);
		MeasurementSummary measure = MeasurementSummary.of(observation == null ? List.of() : List.of(observation));
		ItemResult value = ItemResult.builder()
			.itemId(row.rowId())
			.itemSlug(item.slug())
			.success(captured != null && verdict != null)
			.verdict(verdict)
			.verdictRole(verdict == null ? null : "CANDIDATE")
			.executionDetail(detail)
			.scores(comparison.scored() ? Map.of(config.measureId, comparison.score()) : Map.of())
			.totalTokens(measure.tokens().total())
			.costUsd(measure.costUsd().total())
			.durationMs(observation == null || observation.elapsedNanos() == null ? null
					: observation.elapsedNanos() / 1_000_000)
			.build();
		return value.toBuilder().passed(ItemAccounting.passedFlagCurrent(value.interpretation())).build();
	}

	private JudgeTruth legacyTruth(String label) {
		if (config.truthKind.equals("NUMERIC")) {
			try {
				double value = Double.parseDouble(label);
				if (!Double.isFinite(value))
					return null;
				return new JudgeTruth("NUMERIC", ProducerDocument.parse(Double.toString(value)), null, null,
						"legacy-normalized:v1", 0.0, 1.0, List.of());
			}
			catch (RuntimeException error) {
				return JudgeTruth.simple("NUMERIC", label);
			}
		}
		return JudgeTruth.simple(config.truthKind, label);
	}

	private String invalidTruth(JudgeTruth value) {
		if (value == null || value.value().isNull())
			return "MISSING_TRUTH";
		if (!config.truthKind.equals(value.kind()))
			return "TRUTH_KIND_MISMATCH";
		return switch (value.kind()) {
			case "PROPOSITION" -> value.value().isBoolean() ? null : "INVALID_TRUTH";
			case "NUMERIC" ->
				value.value().isNumber() && Double.isFinite(value.value().doubleValue()) && value.scaleId() != null
						&& value.minimum() != null && value.maximum() != null && value.maximum() > value.minimum()
								? null : "INVALID_TRUTH";
			case "STATUS_LABEL" ->
				value.value().isTextual() && List.of("PASS", "FAIL", "ERROR", "ABSTAIN", "NOT_APPLICABLE")
					.contains(value.label().toUpperCase(Locale.ROOT)) ? null : "INVALID_TRUTH";
			default -> value.value().isTextual() ? null : "INVALID_TRUTH";
		};
	}

	private String unscoredCandidateReason(Judgment actual) {
		if (actual.policyApplication() != null && actual.status() != actual.producerStatus())
			return "WITHHELD_ASSESSMENT";
		return switch (actual.status()) {
			case ABSTAIN -> "ABSTAINED";
			case NOT_APPLICABLE -> "NOT_APPLICABLE";
			case ERROR -> "RETURNED_ERROR";
			default -> "MISSING_COMPONENT";
		};
	}

	private JudgeComparison unscored(@Nullable RecordedJudgment candidate, @Nullable JudgeTruth truth, String reason,
			String explanation) {
		return JudgeComparison.unscored(config.measureId, config.scorerRevision, config.truthKind,
				candidate == null ? null : candidate.sha256(), truth, reason, explanation);
	}

	private Map<String, Double> aggregate(List<ItemResult> items) {
		var mean = items.stream()
			.map(v -> v.scores().get(config.measureId))
			.filter(Objects::nonNull)
			.mapToDouble(Double::doubleValue)
			.average();
		return mean.isPresent() ? Map.of(config.measureId, mean.getAsDouble()) : Map.of();
	}

	/** Optional caller-owned transport/retained-observation adapter. */
	@FunctionalInterface
	public interface ObservationExtractor {

		InvocationObservation extract(RowIdentity row, @Nullable RecordedJudgment candidate, List<FailureFact> failures,
				InvocationObservation basic);

	}

	public static final class Builder {

		private String name, candidateId, datasetVersion, measureId, scorerRevision, truthKind, runId;

		private String captureMode;

		private Judge candidate;

		private List<DatasetItem> items;

		private int replicateCount = 1;

		private Function<DatasetItem, JudgmentContext> input;

		private Function<DatasetItem, String> expected;

		private Function<DatasetItem, JudgeTruth> truth;

		private JudgeScorer scorer;

		private ResultStore resultStore;

		private BiFunction<JudgmentContext, RowIdentity, CaseInputIdentity> identity;

		private ObservationExtractor observationExtractor;

		private Function<Judgment, RecordedJudgment> recorder = RecordedJudgment::from;

		private Builder() {
		}

		private Builder copy() {
			Builder b = new Builder();
			b.name = name;
			b.candidateId = candidateId;
			b.datasetVersion = datasetVersion;
			b.measureId = measureId;
			b.scorerRevision = scorerRevision;
			b.truthKind = truthKind;
			b.runId = runId;
			b.captureMode = captureMode;
			b.candidate = candidate;
			b.items = items;
			b.replicateCount = replicateCount;
			b.input = input;
			b.expected = expected;
			b.truth = truth;
			b.scorer = scorer;
			b.resultStore = resultStore;
			b.identity = identity;
			b.observationExtractor = observationExtractor;
			b.recorder = recorder;
			return b;
		}

		public Builder name(String v) {
			name = v;
			return this;
		}

		public Builder candidate(Judge v) {
			candidate = v;
			return this;
		}

		public Builder candidateId(String v) {
			candidateId = v;
			return this;
		}

		public Builder items(List<DatasetItem> v) {
			items = v;
			return this;
		}

		public Builder datasetVersion(String v) {
			datasetVersion = v;
			return this;
		}

		public Builder input(Function<DatasetItem, JudgmentContext> v) {
			input = v;
			return this;
		}

		public Builder expected(Function<DatasetItem, String> v) {
			expected = v;
			return this;
		}

		public Builder truth(Function<DatasetItem, JudgeTruth> v) {
			truth = v;
			return this;
		}

		public Builder scorer(JudgeScorer v) {
			scorer = v;
			return this;
		}

		public Builder resultStore(ResultStore v) {
			resultStore = v;
			return this;
		}

		public Builder replicateCount(int v) {
			replicateCount = v;
			return this;
		}

		public Builder measureId(String v) {
			measureId = v;
			return this;
		}

		public Builder scorerRevision(String v) {
			scorerRevision = v;
			return this;
		}

		public Builder truthKind(String v) {
			truthKind = v;
			return this;
		}

		public Builder captureMode(String v) {
			captureMode = v;
			return this;
		}

		public Builder runId(String v) {
			runId = v;
			return this;
		}

		public Builder caseInputIdentity(BiFunction<JudgmentContext, RowIdentity, CaseInputIdentity> v) {
			identity = v;
			return this;
		}

		public Builder observationExtractor(ObservationExtractor v) {
			observationExtractor = v;
			return this;
		}

		Builder recorder(Function<Judgment, RecordedJudgment> v) {
			recorder = v;
			return this;
		}

		public JudgeExperiment build() {
			return new JudgeExperiment(this);
		}

	}

}
