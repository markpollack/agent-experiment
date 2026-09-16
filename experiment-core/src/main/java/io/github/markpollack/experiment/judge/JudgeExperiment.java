package io.github.markpollack.experiment.judge;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import io.github.markpollack.experiment.dataset.DatasetItem;
import io.github.markpollack.experiment.result.ExperimentResult;
import io.github.markpollack.experiment.result.ItemResult;
import io.github.markpollack.experiment.result.RecordedJudgment;
import io.github.markpollack.experiment.result.RecordedVerdict;
import io.github.markpollack.experiment.store.ResultStore;
import io.github.markpollack.judge.Judge;
import io.github.markpollack.experiment.attestation.ItemAccounting;
import io.github.markpollack.judge.context.JudgmentContext;
import io.github.markpollack.judge.result.Judgment;
import io.github.markpollack.judge.result.JudgmentReasonCode;
import io.github.markpollack.judge.jury.Verdict;
import io.github.markpollack.judge.jury.interpretation.Interpretation;
import io.github.markpollack.judge.jury.interpretation.Verdicts;

/**
 * Runs a structured experiment where the system-under-test is a Judge. Iterates a labeled
 * dataset through the candidate judge, then scores each judgment against expected labels.
 *
 * <p>
 * Sibling typed experiment API alongside {@code AgentExperiment}, using shared experiment
 * infrastructure (Dataset, ExperimentResult, ResultStore, ComparisonEngine).
 */
public final class JudgeExperiment {

	private final String name;

	private final Judge candidate;

	private final List<DatasetItem> items;

	private final String datasetVersion;

	private final Function<DatasetItem, JudgmentContext> input;

	private final Function<DatasetItem, String> expected;

	private final JudgeScorer scorer;

	private final ResultStore resultStore;

	private JudgeExperiment(Builder builder) {
		this.name = java.util.Objects.requireNonNull(builder.name, "name must not be null");
		this.candidate = java.util.Objects.requireNonNull(builder.candidate, "candidate must not be null");
		this.items = List.copyOf(java.util.Objects.requireNonNull(builder.items, "items must not be null"));
		this.datasetVersion = builder.datasetVersion != null ? builder.datasetVersion : "1.0.0";
		this.input = java.util.Objects.requireNonNull(builder.input, "input must not be null");
		this.expected = java.util.Objects.requireNonNull(builder.expected, "expected must not be null");
		this.scorer = java.util.Objects.requireNonNull(builder.scorer, "scorer must not be null");
		this.resultStore = java.util.Objects.requireNonNull(builder.resultStore, "resultStore must not be null");
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Run the judge experiment.
	 * @return typed result with agreement rate and disagreement list
	 */
	public JudgeExperimentResult run() {
		List<ItemResult> itemResults = items.stream().map(this::runItem).toList();

		Map<String, Double> aggregateScores = aggregateScores(itemResults);

		ExperimentResult experimentResult = ExperimentResult.builder()
			.experimentId(UUID.randomUUID().toString())
			.experimentName(name)
			.datasetSemanticVersion(datasetVersion)
			.timestamp(Instant.now())
			.items(itemResults)
			.metadata(Map.of("experimentType", "judge", "candidateJudge", candidate.getClass().getSimpleName(),
					"scorer", scorer.getClass().getSimpleName()))
			.aggregateScores(aggregateScores)
			.counts(ItemAccounting.count(itemResults))
			.build();

		resultStore.save(experimentResult);
		return JudgeExperimentResult.from(experimentResult);
	}

	private ItemResult runItem(DatasetItem item) {
		JudgmentContext context = input.apply(item);
		Judgment actual = candidate.judge(context);
		String expectedLabel = expected.apply(item);

		JudgeScorerResult scorerResult = scorer.score(new JudgeScoringInput(item, actual, expectedLabel));

		Verdict verdict = toVerdict(scorerResult, actual);
		RecordedVerdict recorded = RecordedVerdict.from(verdict);
		Interpretation interpretation = Verdicts.interpret(verdict);

		return ItemResult.builder()
			.itemId(item.id())
			.itemSlug(item.slug())
			// Null when nothing was decided about the candidate, never false.
			.passed(ItemAccounting.passedFlag(interpretation))
			// An unscored comparison records no agreement score rather than a zero one.
			.scores(scorerResult.scored() ? Map.of("agreement", scorerResult.score()) : Map.<String, Double>of())
			.verdict(recorded)
			.interpretation(interpretation)
			.executionDetail(new JudgeExecutionDetail(RecordedJudgment.from(actual), expectedLabel, scorerResult))
			.metadata(Map.of("experimentType", "judge", "expectedLabel", expectedLabel))
			.build();
	}

	/**
	 * Turn a scoring result into the verdict that gets stored.
	 *
	 * <p>
	 * When the scorer could not compare — the candidate errored, abstained, or found the
	 * criterion inapplicable — the verdict mirrors the candidate's own status instead of
	 * manufacturing a pass or a fail. The counting rules then apply on their own terms: a
	 * not-applicable item leaves the denominator, an error is excluded and counted as an
	 * instrument failure rather than scored against the subject, and an abstention counts
	 * against it.
	 */
	private Verdict toVerdict(JudgeScorerResult scorerResult, Judgment candidate) {
		if (scorerResult.scored()) {
			Judgment judgment = Judgment.verdict(scorerResult.match())
				.score(scorerResult.score())
				.reasoning(scorerResult.reasoning())
				.build();
			return Verdict.single("scorer", judgment);
		}
		Judgment mirrored = switch (candidate.status()) {
			case NOT_APPLICABLE -> Judgment.notApplicable(scorerResult.reasoning());
			case ERROR -> Judgment.error(JudgmentReasonCode.JUDGE_REPORTED, scorerResult.reasoning());
			// A candidate that reached a verdict the scorer still could not compare has
			// left the comparison undecided, which is what an abstention says.
			case ABSTAIN, PASS, FAIL -> Judgment.abstain(scorerResult.reasoning());
		};
		return Verdict.single("scorer", mirrored);
	}

	private static Map<String, Double> aggregateScores(List<ItemResult> items) {
		if (items.isEmpty()) {
			return Map.of();
		}
		java.util.Set<String> scoreNames = new java.util.LinkedHashSet<>();
		for (ItemResult item : items) {
			scoreNames.addAll(item.scores().keySet());
		}
		Map<String, Double> aggregates = new LinkedHashMap<>();
		for (String scoreName : scoreNames) {
			double sum = 0;
			int count = 0;
			for (ItemResult item : items) {
				Double score = item.scores().get(scoreName);
				if (score != null) {
					sum += score;
					count++;
				}
			}
			if (count > 0) {
				aggregates.put(scoreName, sum / count);
			}
		}
		return Map.copyOf(aggregates);
	}

	public static final class Builder {

		private String name;

		private Judge candidate;

		private List<DatasetItem> items;

		private String datasetVersion;

		private Function<DatasetItem, JudgmentContext> input;

		private Function<DatasetItem, String> expected;

		private JudgeScorer scorer;

		private ResultStore resultStore;

		private Builder() {
		}

		public Builder name(String name) {
			this.name = name;
			return this;
		}

		public Builder candidate(Judge candidate) {
			this.candidate = candidate;
			return this;
		}

		public Builder items(List<DatasetItem> items) {
			this.items = items;
			return this;
		}

		public Builder datasetVersion(String datasetVersion) {
			this.datasetVersion = datasetVersion;
			return this;
		}

		public Builder input(Function<DatasetItem, JudgmentContext> input) {
			this.input = input;
			return this;
		}

		public Builder expected(Function<DatasetItem, String> expected) {
			this.expected = expected;
			return this;
		}

		public Builder scorer(JudgeScorer scorer) {
			this.scorer = scorer;
			return this;
		}

		public Builder resultStore(ResultStore resultStore) {
			this.resultStore = resultStore;
			return this;
		}

		public JudgeExperiment build() {
			return new JudgeExperiment(this);
		}

	}

}
