package io.github.markpollack.experiment.judge;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.markpollack.experiment.dataset.DatasetItem;
import io.github.markpollack.experiment.result.*;
import io.github.markpollack.experiment.recording.*;
import io.github.markpollack.experiment.store.*;
import io.github.markpollack.experiment.comparison.*;
import io.github.markpollack.judge.Judge;
import io.github.markpollack.judge.context.JudgmentContext;
import io.github.markpollack.judge.result.*;
import io.github.markpollack.judge.jury.Verdict;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/**
 * Deterministic contract exhibits: all successful recordings traverse the production
 * store.
 */
class RecordingExhibitsTest {

	@TempDir
	Path root;

	FileSystemResultStore store;

	ObjectMapper mapper = RecordingVectorsTest.mapper();

	int runSequence;

	@BeforeEach
	void setup() {
		store = new FileSystemResultStore(root.resolve("results"));
	}

	@AfterEach
	void retain(TestInfo info) throws Exception {
		Thread.interrupted();
		try (var paths = Files.walk(root)) {
			for (Path path : paths.filter(Files::isRegularFile).toList())
				RecordingVectorsTest.preserveEvidence(path,
						"exhibits/" + info.getTestMethod().orElseThrow().getName() + "/" + root.relativize(path));
		}
	}

	List<DatasetItem> cases(int size) {
		List<DatasetItem> items = new ArrayList<>();
		for (int i = 0; i < size; i++)
			items.add(new DatasetItem("case-" + i, "case-" + i, "evidence-" + i, "fixture", "A", false, List.of(),
					List.of(), "active", root, null, null));
		return items;
	}

	JudgeExperiment.Builder builder(String candidate, Judge judge, int size) {
		return JudgeExperiment.builder()
			.name("exhibits")
			.runId("run-" + (++runSequence))
			.candidateId(candidate)
			.captureMode("FAKE")
			.candidate(judge)
			.items(cases(size))
			.input(item -> JudgmentContext.builder().goal(item.id()).build())
			.caseInputIdentity((input, row) -> new CaseInputIdentity("fixture", input.goal(), "v1", null,
					"effective-case-input:v1"))
			.truth(item -> JudgeTruth.proposition(true))
			.scorer(JudgeScorers.propositionMatch())
			.resultStore(store);
	}

	JudgeExperimentResult run(JudgeExperiment.Builder builder) throws Exception {
		var original = builder.build().run().asExperimentResult();
		var loaded = store.load(original.experimentId()).orElseThrow();
		assertThat(loaded).isEqualTo(original);
		assertThat(store.listByName("exhibits")).contains(loaded);
		assertThat(store.mostRecent("exhibits")).contains(loaded);
		var summary = new DefaultComparisonEngine(store).summarize(loaded);
		assertThat(summary.measures().verdictRole()).isEqualTo("CANDIDATE");
		assertThat(summary.measures().comparisonRule()).isEqualTo("scored-comparisons:v1");
		assertThat(summary.totalTokens()).isEqualTo(loaded.totalTokens());
		assertThat(summary.totalCostUsd()).isEqualTo(loaded.totalCostUsd());
		var sessions = new FileSystemSessionStore(root.resolve("sessions"));
		sessions.createSession(loaded.experimentId(), "exhibits", Map.of());
		sessions.saveVariantToSession(loaded.experimentId(), "exhibits", "candidate", loaded);
		var entry = sessions.loadSession("exhibits", loaded.experimentId()).orElseThrow().variants().getFirst();
		assertThat(entry.measures()).isEqualTo(summary.measures());
		assertThat(entry.costUsd()).isEqualTo(loaded.totalCostUsd());
		assertThat(entry.totalTokens()).isEqualTo(loaded.totalTokens());
		assertThat(sessions.listSessions("exhibits")).isNotEmpty();
		assertThat(sessions.mostRecentSession("exhibits")).isPresent();
		return JudgeExperimentResult.from(loaded);
	}

	JudgeExecutionDetail detail(JudgeExperimentResult run, int index) {
		return (JudgeExecutionDetail) run.experimentResult().items().get(index).executionDetail();
	}

	UsageObservation usage(String id, Long input, Long output, Double cost, String currency) {
		return new UsageObservation(id, "fixture-route", "fixture-response:/usage", "adapter-request", input, output,
				"input-plus-output:v1", cost, currency, null);
	}

	JudgeExperiment.Builder measured(JudgeExperiment.Builder b, UsageObservation... usage) {
		return b.observationExtractor((row, candidate, failures, basic) -> basic.withUsage(List.of(usage)));
	}

	ExperimentResult storeSubject(String id, RecordedVerdict verdict) {
		store.save(RecordingVectorsTest.subject(id, verdict));
		return store.load(id).orElseThrow();
	}

	static Judgment nativeFinding(boolean value, String reason) {
		return new Judgment(value ? JudgmentStatus.PASS : JudgmentStatus.FAIL,
				new Assessment(new Proposition(value), null, null), null, null, null, reason, List.of(), null, null,
				Map.of());
	}

	@Test
	void E01_associationsRemainOutsideStrictProducerDocuments() throws Exception {
		String source = RecordingVectorsTest.vectorSource("escalation-accepted-negative");
		var verdict = RecordedVerdict.fromProducerJson(source)
			.withAssociations(List.of(new ProducerDocument.Association("", "instrument-root"),
					new ProducerDocument.Association("/compositeAttempts/0/verdict", "instrument-child")));
		var loaded = storeSubject("associations", verdict).items().getFirst();
		assertThat(loaded.verdict().producerJson()).isEqualTo(source);
		assertThat(loaded.verdict().document().associations()).hasSize(2);
		assertThat(loaded.interpretation().readingSupport().name()).isEqualTo("SUPPORTED");
		assertThat(loaded.verdict().document().node().has("instrumentHash")).isFalse();
	}

	@Test
	void E02_negativeCandidateMatchesNegativeTruth() throws Exception {
		var result = run(builder("negative", ctx -> nativeFinding(false, "false"), 1)
			.truth(item -> JudgeTruth.proposition(false)));
		assertThat(detail(result, 0).candidateJudgment()
			.document()
			.node()
			.at("/assessment/proposition/value")
			.booleanValue()).isFalse();
		assertThat(result.experimentResult().items().getFirst().passed()).isFalse();
		assertThat(detail(result, 0).comparison().match()).isTrue();
		assertThat(result.agreementRate()).isEqualTo(1.0);
	}

	@Test
	void E03_scoredOnlyDenominator() throws Exception {
		var result = run(builder("mixed",
				ctx -> ctx.goal().endsWith("0") ? nativeFinding(true, "yes") : Judgment.abstain("unknown"), 2));
		assertThat(result.agreementRate()).isEqualTo(1.0);
		assertThat(result.scoredCount()).isEqualTo(1);
		assertThat(result.unscoredCount()).isEqualTo(1);
		assertThat(result.disagreements()).isEmpty();
		assertThat(result.experimentResult().counts().passRate()).hasValue(0.5);
	}

	@Test
	void E04_allUnscoredHaveNoRateOrMean() throws Exception {
		var result = run(builder("unknown", ctx -> Judgment.abstain("unknown"), 2)
			.truth(item -> item.id().endsWith("0") ? null : JudgeTruth.proposition(true)));
		assertThat(result.agreementRate()).isNull();
		assertThat(result.meanScore()).isNull();
		assertThat(result.disagreements()).isEmpty();
		assertThat(detail(result, 0).comparison().unscoredReason()).isEqualTo("MISSING_TRUTH");
		assertThat(detail(result, 1).comparison().unscoredReason()).isEqualTo("ABSTAINED");
		assertThat(result.experimentResult().aggregateScores()).isEmpty();
	}

	@Test
	void E05_booleanAgreementAndMeanAreSeparate() throws Exception {
		var result = run(builder("custom", ctx -> nativeFinding(true, "yes"), 2)
			.scorer(input -> new JudgeScorerResult(input.item().id().endsWith("0"),
					input.item().id().endsWith("0") ? .8 : .4, "fixture"))
			.measureId("graded-match:v1")
			.scorerRevision("fixture:v1")
			.truthKind("PROPOSITION"));
		assertThat(result.agreementRate()).isEqualTo(.5);
		assertThat(result.meanScore()).isCloseTo(.6, within(1e-15));
	}

	@Test
	void E06_returnedErrorThrownAndNullAreDistinct() throws Exception {
		var result = run(builder("errors", ctx -> {
			if (ctx.goal().endsWith("0"))
				return Judgment.error("returned");
			if (ctx.goal().endsWith("1"))
				throw new IllegalStateException("thrown");
			return null;
		}, 3).truth(item -> JudgeTruth.status("ERROR")).scorer(JudgeScorers.exactVerdictMatch()));
		assertThat(detail(result, 0).invocationState()).isEqualTo("RETURNED");
		assertThat(detail(result, 0).comparison().match()).isTrue();
		assertThat(detail(result, 1).invocationState()).isEqualTo("THREW");
		assertThat(detail(result, 2).invocationState()).isEqualTo("NULL_RETURN");
		assertThat(detail(result, 1).candidateJudgment()).isNull();
		assertThat(detail(result, 2).candidateJudgment()).isNull();
		assertThat(result.scoredCount()).isEqualTo(1);
		var semantic = run(builder("semantic", ctx -> Judgment.error("returned"), 1));
		assertThat(semantic.scoredCount()).isZero();
	}

	@Test
	void E07_truthAndScorerFailuresRetainCandidate() throws Exception {
		for (String phase : List.of("TRUTH", "SCORING")) {
			var b = builder("failure", ctx -> nativeFinding(true, "returned"), 1);
			if (phase.equals("TRUTH"))
				b.truth(item -> {
					throw new IllegalArgumentException("truth unavailable");
				});
			else
				b.scorer(input -> {
					throw new IllegalStateException("scorer failed");
				}).measureId("custom:v1").scorerRevision("1").truthKind("PROPOSITION");
			var result = run(b);
			assertThat(result.experimentResult().items().getFirst().success()).isTrue();
			assertThat(detail(result, 0).candidateJudgment()).isNotNull();
			assertThat(detail(result, 0).comparison().unscoredReason()).isEqualTo(phase + "_FAILED");
			assertThat(detail(result, 0).observation().elapsedNanos()).isNotNegative();
		}
	}

	@Test
	void E08_inputFailureRetainsEntireDeclaredPopulation() throws Exception {
		var result = run(builder("input", ctx -> nativeFinding(true, "ok"), 3).input(item -> {
			if (item.id().endsWith("0"))
				throw new IllegalArgumentException("bad input");
			return JudgmentContext.builder().goal(item.id()).build();
		}));
		assertThat(result.experimentResult().declaredRows()).isEqualTo(3);
		assertThat(result.experimentResult().attemptedRows()).isEqualTo(3);
		assertThat(detail(result, 0).invocationState()).isEqualTo("NOT_ATTEMPTED");
		assertThat(detail(result, 0).rowState()).isEqualTo("FAILED");
		assertThat(detail(result, 0).observation()).isNull();
		assertThat(result.scoredCount()).isEqualTo(2);
		assertThat(result.experimentResult().runState()).isEqualTo("COMPLETE");
	}

	@Test
	void E09_cancellationRetainsCapturedReturnAndRemainingDeclarations() throws Exception {
		for (boolean afterReturn : List.of(false, true)) {
			var b = builder("cancel", ctx -> {
				if (!afterReturn && ctx.goal().endsWith("1")) {
					Thread.currentThread().interrupt();
					throw new java.util.concurrent.CancellationException("stop");
				}
				return nativeFinding(true, "captured");
			}, 3);
			if (afterReturn)
				b.truth(item -> {
					if (item.id().endsWith("1")) {
						Thread.currentThread().interrupt();
						throw new java.util.concurrent.CancellationException("stop after return");
					}
					return JudgeTruth.proposition(true);
				});
			var original = b.build().run();
			assertThat(Thread.interrupted()).isTrue();
			var result = JudgeExperimentResult
				.from(store.load(original.experimentResult().experimentId()).orElseThrow());
			assertThat(result.experimentResult().runState()).isEqualTo("CANCELLED");
			assertThat(result.experimentResult().items()).hasSize(3);
			assertThat(result.experimentResult().attemptedRows()).isEqualTo(2);
			assertThat(detail(result, 1).rowState()).isEqualTo("CANCELLED");
			assertThat(detail(result, 1).invocationState()).isEqualTo(afterReturn ? "RETURNED" : "THREW");
			assertThat(detail(result, 1).candidateJudgment() != null).isEqualTo(afterReturn);
			assertThat(detail(result, 2).rowState()).isEqualTo("NOT_ATTEMPTED");
			assertThat(result.agreementRate()).isNull();
			assertThat(result.meanScore()).isNull();
			assertThat(result.partialAgreementRate()).isEqualTo(1.0);
			assertThat(new DefaultComparisonEngine(store).summarize(result.experimentResult()).passRate()).isNull();
		}
	}

	@Test
	void cancellationFlagWithoutExceptionStillMarksTheCurrentCapturedRow() throws Exception {
		var result = builder("flag", context -> nativeFinding(true, "captured"), 3).truth(item -> {
			if (item.id().endsWith("1"))
				Thread.currentThread().interrupt();
			return JudgeTruth.proposition(true);
		}).build().run();
		assertThat(Thread.interrupted()).isTrue();
		var loaded = JudgeExperimentResult.from(store.load(result.experimentResult().experimentId()).orElseThrow());
		assertThat(detail(loaded, 1).rowState()).isEqualTo("CANCELLED");
		assertThat(detail(loaded, 1).invocationState()).isEqualTo("RETURNED");
		assertThat(detail(loaded, 2).rowState()).isEqualTo("NOT_ATTEMPTED");
	}

	@Test
	void E10_persistenceFailuresAndCollisionsAreVisible() throws Exception {
		var result = run(builder("persist", ctx -> nativeFinding(true, "ok"), 1));
		Path file = root.resolve("results/exhibits/" + result.experimentResult().experimentId() + ".json");
		byte[] before = Files.readAllBytes(file);
		assertThatThrownBy(() -> store.save(result.experimentResult())).isInstanceOf(ResultStoreException.class);
		assertThat(Files.readAllBytes(file)).isEqualTo(before);
		Files.writeString(root.resolve("results/exhibits/index.json"), "malformed-index");
		assertThatThrownBy(() -> builder("failed", ctx -> nativeFinding(true, "ok"), 1).build().run())
			.isInstanceOf(ResultStoreException.class);
		assertThat(store.load("run-2")).isEmpty();
		assertThat(Files.readAllBytes(file)).isEqualTo(before);
	}

	@Test
	void E11_replicatesAlignAcrossCandidateContrast() throws Exception {
		var left = run(builder("candidate:a", ctx -> nativeFinding(true, "ok"), 1).replicateCount(2));
		var right = run(builder("candidate:b", ctx -> nativeFinding(false, "no"), 1).replicateCount(2));
		var comparison = new DefaultComparisonEngine(store).compare(left.experimentResult(), right.experimentResult());
		assertThat(comparison.itemDiffs()).hasSize(2).allSatisfy(diff -> {
			assertThat(diff.status()).isEqualTo(DiffStatus.IMPROVED);
			assertThat(diff.currentRowId()).isNotEqualTo(diff.baselineRowId());
		});
		assertThat(comparison.currentCandidateId()).isEqualTo("candidate:a");
		assertThat(left.experimentResult().items().stream().map(ItemResult::itemId).distinct().count()).isEqualTo(2);
	}

	@Test
	void E12_unknownUsageAndObservedZeroStayDifferentEverywhere() throws Exception {
		var unknown = run(builder("unknown", ctx -> nativeFinding(true, "ok"), 1));
		var zero = run(
				measured(builder("zero", ctx -> nativeFinding(true, "ok"), 1), usage("response", 0L, 0L, 0.0, "USD")));
		assertThat(unknown.experimentResult().totalTokens()).isNull();
		assertThat(unknown.experimentResult().totalCostUsd()).isNull();
		assertThat(zero.experimentResult().totalTokens()).isZero();
		assertThat(zero.experimentResult().totalCostUsd()).isZero();
		assertThat(new DefaultComparisonEngine(store).compare(unknown.experimentResult(), zero.experimentResult())
			.summary()
			.totalCostUsd()).isNull();
	}

	@Test
	void E13_scopedUsageDeduplicatesAndConflictsInvalidateTotals() throws Exception {
		var u = usage("same", 7L, 3L, null, null);
		var dedup = run(measured(builder("same", ctx -> nativeFinding(true, "ok"), 1), u, u));
		assertThat(dedup.experimentResult().totalTokens()).isEqualTo(10L);
		var conflict = run(measured(builder("conflict", ctx -> nativeFinding(true, "ok"), 1), u,
				usage("same", 9L, 3L, null, null)));
		assertThat(conflict.experimentResult().totalTokens()).isNull();
		assertThat(conflict.experimentResult().measurements().tokens().conflicted()).isEqualTo(1);
	}

	@Test
	void E14_partialOverflowMixedCurrencyAndUnknownCostHaveNoCompleteTotal() throws Exception {
		var partial = run(measured(builder("partial", ctx -> nativeFinding(true, "ok"), 1),
				usage("partial", 7L, null, null, null)));
		assertThat(partial.experimentResult().totalTokens()).isNull();
		assertThat(partial.experimentResult().measurements().tokens().knownSubtotal()).isEqualTo(7L);
		var overflow = run(measured(builder("overflow", ctx -> nativeFinding(true, "ok"), 1),
				usage("overflow", Long.MAX_VALUE, 1L, null, null)));
		assertThat(overflow.experimentResult().totalTokens()).isNull();
		assertThat(overflow.experimentResult().measurements().tokens().knownSubtotal()).isNull();
		var currency = run(measured(builder("currency", ctx -> nativeFinding(true, "ok"), 1),
				usage("usd", 1L, 1L, 2.0, "USD"), usage("eur", 1L, 1L, 3.0, "EUR")));
		assertThat(currency.experimentResult().totalCostUsd()).isNull();
		assertThat(currency.experimentResult().measurements().costUsd().knownSubtotal()).isEqualTo(2.0);
	}

	@Test
	void E15_fakeFailureTimingAndRetainedOriginalObservation() throws Exception {
		var failed = run(builder("fake", ctx -> {
			throw new IllegalStateException("fake failure");
		}, 1));
		assertThat(detail(failed, 0).observation().captureMode()).isEqualTo("FAKE");
		assertThat(detail(failed, 0).observation().elapsedNanos()).isNotNegative();
		Instant old = Instant.parse("2026-01-01T00:00:00Z");
		var retained = run(builder("retained", ctx -> nativeFinding(true, "retained"), 1).captureMode("RETAINED")
			.observationExtractor((row, candidate, failures, basic) -> new InvocationObservation(basic.invocationId(),
					"RETAINED", old, old.plusSeconds(1), 1_000_000_000L, 2, "original-request", 900_000_000L, List.of(),
					List.of())));
		assertThat(detail(retained, 0).observation().startedAt()).isEqualTo(old);
		assertThat(detail(retained, 0).observation().captureMode()).isEqualTo("RETAINED");
		assertThat(detail(retained, 0).observation().transportElapsedNanos()).isEqualTo(900_000_000L);
	}

	@Test
	void E16_namedHistoricalFixturesRemainReadableAndImmutable() throws Exception {
		Map<String, String> fixtures = Map.of("conformance/no-items.json",
				"a03d5a5b6d971e530180e6dafde4186997779f467df282c4dda5df754daf2d0c", "conformance/nothing-scored.json",
				"2220dbbb08e3a72ddc3513dad89a83c780718979a829b8b595a384c06d098ab3", "conformance/never-judged.json",
				"81581864ce8a6bd1d798b86ebc8de898a10d8b1485558ef544693545bd9bd38c", "conformance/all-failed.json",
				"a7fc0eaaf70a474f6542cff7f88a001e965f774aea80799157bf9a9a5a47014e",
				"compatibility/experiment-result-0.5-judge-0.13.json",
				"439a63e4e840c825ebf7368a3957d89ee933a015de47dc800e4057e56c4df460");
		List<Map<String, Object>> index = new ArrayList<>();
		Path directory = root.resolve("results/historical");
		Files.createDirectories(directory);
		for (var fixture : fixtures.entrySet()) {
			byte[] bytes = getClass().getResourceAsStream("/" + fixture.getKey()).readAllBytes();
			assertThat(ProducerDocument.hash(bytes)).isEqualTo(fixture.getValue());
			JsonNode original = mapper.readTree(bytes);
			String id = original.path("experimentId").asText();
			Path file = directory.resolve(id + ".json");
			Files.write(file, bytes);
			index.add(Map.of("experimentId", id, "timestamp", original.path("timestamp").asText(), "fileName",
					id + ".json"));
			var loaded = store.load(id).orElseThrow();
			assertThat(loaded.recordingSchemaVersion()).isNull();
			for (int i = 0; i < loaded.items().size(); i++) {
				var item = loaded.items().get(i);
				var before = original.path("items").get(i);
				if (item.verdict() != null) {
					assertThat(item.verdict().document().node()).isEqualTo(before.path("verdict"));
					assertThat(item.interpretationSnapshot().state()).isEqualTo("LEGACY_UNVERIFIED");
					assertThat(item.interpretationSnapshot().document())
						.isEqualTo(before.has("interpretation") ? before.get("interpretation") : NullNode.instance);
				}
			}
			assertThatThrownBy(() -> store.save(loaded)).isInstanceOf(ResultStoreException.class);
			assertThat(Files.readAllBytes(file)).isEqualTo(bytes);
		}
		Files.writeString(directory.resolve("index.json"),
				mapper.writeValueAsString(Map.of("experimentName", "historical", "entries", index)));
		assertThat(store.listByName("historical")).hasSize(5);
		assertThat(store.mostRecent("historical")).isPresent();
	}

	@Test
	void copiedHistoricalReadingKeepsOriginalShapeAndCannotSupplyNewScores() throws Exception {
		var historical = mapper.readValue(
				getClass().getResourceAsStream("/compatibility/experiment-result-0.5-judge-0.13.json"),
				ExperimentResult.class);
		var reevaluator = io.github.markpollack.experiment.reeval.ReEvaluator.builder()
			.resultStore(store)
			.contextFactory(item -> Optional.empty())
			.build();
		var derivative = reevaluator.reEvaluate(historical,
				io.github.markpollack.judge.jury.SimpleJury.builder()
					.judge(context -> nativeFinding(true, "unused"), 1.0)
					.votingStrategy(new io.github.markpollack.judge.jury.MajorityVotingStrategy())
					.build());
		var loaded = store.load(derivative.experimentId()).orElseThrow();
		assertThat(loaded.metadata()).containsEntry("reEvaluatedFrom", historical.experimentId());
		assertThat(loaded.aggregateScores()).isEmpty();
		assertThat(loaded.items().getFirst().scores()).isEmpty();
		assertThat(loaded.items().getFirst().interpretationSnapshot().document())
			.isEqualTo(historical.items().getFirst().interpretationSnapshot().document());
		assertThat(loaded.items().getFirst().interpretationSnapshot().state()).isEqualTo("LEGACY_UNVERIFIED");
		assertThat(loaded.items().getFirst().metadata()).containsKey("originalJudgeExecutionDetail");
		assertThat(loaded.totalCostUsd()).isNull();
		assertThat(loaded.totalTokens()).isNull();
	}

	@Test
	void E17_interpretationBindingAndContentAreCheckedOnEveryRead() throws Exception {
		var result = run(builder("binding", ctx -> nativeFinding(true, "ok"), 1));
		var original = (ObjectNode) mapper.valueToTree(result.experimentResult());
		List<Consumer<ObjectNode>> mutations = List.of(
				n -> ((ObjectNode) n.at("/items/0/interpretation")).put("sourceSha256", "0".repeat(64)),
				n -> ((ObjectNode) n.at("/items/0/interpretation")).put("producerRevision", "different"),
				n -> ((ObjectNode) n.at("/items/0/interpretation")).put("interpreterArtifactSha256", "0".repeat(64)),
				n -> ((ObjectNode) n.at("/items/0/interpretation/document")).put("reading", "REJECTED"),
				n -> ((ObjectNode) n.at("/items/0/verdict")).put("sha256", "0".repeat(64)));
		assertInvalidSavedMutations(result.experimentResult(), original, mutations);
	}

	@Test
	void E18_envelopeAndDetailDispatchNeverGuessHistoricalFormat() throws Exception {
		var result = run(builder("dispatch", ctx -> nativeFinding(true, "ok"), 1));
		var original = (ObjectNode) mapper.valueToTree(result.experimentResult());
		assertInvalidSavedMutations(result.experimentResult(), original,
				List.of(n -> n.put("recordingSchemaVersion", 2.0), n -> n.put("recordingSchemaVersion", 99),
						n -> n.putNull("recordingSchemaVersion"), n -> n.put("recordingSchemaVersion", "2"),
						n -> ((ObjectNode) n.at("/items/0/executionDetail")).put("kind", "UNKNOWN"),
						n -> ((ObjectNode) n.at("/items/0/executionDetail")).remove("kind")));
	}

	@Test
	void E19_unknownNullAndAbsentAssessmentKeepSourceSpelling() throws Exception {
		Judgment unknown = new Judgment(JudgmentStatus.PASS, new Assessment(new Proposition(null), null, null), null,
				null, null, "unknown", List.of(), null, null, Map.of());
		var result = run(builder("unknown", ctx -> unknown, 1));
		JsonNode live = detail(result, 0).candidateJudgment().document().node();
		assertThat(live.at("/assessment/proposition").isObject()).isTrue();
		assertThat(live.at("/assessment/proposition").isEmpty()).isTrue();
		assertThat(result.scoredCount()).isZero();
		ObjectNode explicit = (ObjectNode) ProducerDocument
			.parse(ProducerDocument.json(Verdict.single("candidate", unknown)));
		for (String pointer : List.of("/aggregated/assessment/proposition", "/individual/0/assessment/proposition",
				"/individualByName/candidate/assessment/proposition"))
			((ObjectNode) explicit.at(pointer)).putNull("value");
		var loaded = storeSubject("explicit-null", RecordedVerdict.fromProducerJson(explicit.toString()));
		assertThat(loaded.items()
			.getFirst()
			.verdict()
			.document()
			.node()
			.at("/aggregated/assessment/proposition")
			.has("value")).isTrue();
		var absent = storeSubject("absent",
				RecordedVerdict.from(Verdict.single("candidate", Judgment.notApplicable("absent"))));
		assertThat(absent.items().getFirst().verdict().document().node().path("aggregated").has("assessment"))
			.isFalse();
	}

	@Test
	void E20_outOfRangeCardinalityAndFractionalProducerVersionAreNotCoerced() throws Exception {
		ObjectNode source = (ObjectNode) ProducerDocument
			.parse(RecordingVectorsTest.vectorSource("negative-ordinal-identity"));
		source.put("declaredCardinality", 2147483648L);
		var large = storeSubject("large", RecordedVerdict.fromProducerJson(source.toString())).items().getFirst();
		assertThat(large.verdict().document().node().path("declaredCardinality").longValue()).isEqualTo(2147483648L);
		assertThat(large.interpretation().reading()).isNull();
		assertThat(large.interpretation().readingSupport().name()).isEqualTo("UNDETERMINED");
		var fractional = storeSubject("fractional",
				RecordedVerdict.fromProducerJson(RecordingVectorsTest.vectorSource("fractional-version")))
			.items()
			.getFirst();
		assertThat(fractional.verdict().document().node().path("schemaVersion").doubleValue()).isEqualTo(2.9);
		assertThat(fractional.interpretation().sourceVersion()).isEqualTo(-1);
		assertThat(fractional.passed()).isNull();
	}

	@Test
	void E21_duplicateProducerMembersFailBeforeInterpretation() throws Exception {
		assertThatThrownBy(() -> RecordedVerdict.fromProducerJson("{\"schemaVersion\":2,\"schemaVersion\":99}"))
			.isInstanceOf(IllegalArgumentException.class);
		var valid = run(builder("dup", ctx -> nativeFinding(true, "ok"), 1));
		ObjectNode n = mapper.valueToTree(valid.experimentResult());
		String duplicate = "{\"schemaVersion\":99,\"schemaVersion\":2}";
		((ObjectNode) n.at("/items/0/verdict")).put("producerJson", duplicate)
			.put("sha256", ProducerDocument.hash(duplicate.getBytes(StandardCharsets.UTF_8)));
		assertThatThrownBy(() -> mapper.treeToValue(n, ExperimentResult.class)).isInstanceOf(Exception.class);
	}

	@Test
	void E22_legacyAgreementCannotJoinCurrentSemanticSeries() throws Exception {
		var current = run(builder("current", ctx -> nativeFinding(true, "ok"), 1));
		var historical = mapper.readValue(
				getClass().getResourceAsStream("/compatibility/experiment-result-0.5-judge-0.13.json"),
				ExperimentResult.class);
		assertThat(new DefaultComparisonEngine(store).summarize(historical).measures().countRule())
			.isEqualTo("legacy-recorded");
		assertThatThrownBy(() -> new DefaultComparisonEngine(store).compare(current.experimentResult(), historical))
			.isInstanceOf(IncomparableConditionsException.class)
			.hasMessageContaining("legacy");
	}

	@Test
	void E23_recordingFailureDoesNotInventOrLoseInvocationState() throws Exception {
		var result = run(builder("recording-fails", ctx -> nativeFinding(true, "returned"), 1).recorder(value -> {
			throw new IllegalArgumentException("cannot serialize");
		}));
		assertThat(detail(result, 0).invocationState()).isEqualTo("RETURNED");
		assertThat(detail(result, 0).candidateJudgment()).isNull();
		assertThat(result.experimentResult().items().getFirst().success()).isFalse();
		assertThat(detail(result, 0).failures()).anySatisfy(f -> assertThat(f.phase()).isEqualTo("RECORDING"));
		assertThat(result.scoredCount()).isZero();
		assertThat(detail(result, 0).observation()).isNotNull();
	}

	@Test
	void E24_knownChangedEffectiveInputsRejectBeforeCandidateDeltas() throws Exception {
		var baseline = run(builder("old", ctx -> nativeFinding(false, "no"), 1));
		var changed = run(builder("new", ctx -> nativeFinding(true, "yes"), 1).caseInputIdentity(
				(ctx, row) -> new CaseInputIdentity("fixture", ctx.goal(), "v2", null, "effective-case-input:v1")));
		assertThatThrownBy(() -> new DefaultComparisonEngine(store).compare(changed.experimentResult(),
				baseline.experimentResult()))
			.isInstanceOf(IncomparableConditionsException.class)
			.hasMessageContaining("INCOMPATIBLE")
			.hasMessageContaining(baseline.experimentResult().experimentId())
			.hasMessageContaining(changed.experimentResult().items().getFirst().itemId());
		var a = run(builder("a", ctx -> nativeFinding(false, "no"), 1).caseInputIdentity((ctx,
				row) -> new CaseInputIdentity("fixture", ctx.goal(), "v1", "a".repeat(64), "effective-case-input:v1")));
		var b = run(builder("b", ctx -> nativeFinding(true, "yes"), 1).caseInputIdentity((ctx,
				row) -> new CaseInputIdentity("fixture", ctx.goal(), "v1", "b".repeat(64), "effective-case-input:v1")));
		assertThatThrownBy(() -> new DefaultComparisonEngine(store).compare(a.experimentResult(), b.experimentResult()))
			.hasMessageContaining("INCOMPATIBLE");
	}

	@Test
	void E25_absentAndAsymmetricInputIdentityAreUnknown() throws Exception {
		var known = run(builder("known", ctx -> nativeFinding(true, "yes"), 1));
		var missing = run(
				builder("missing", ctx -> nativeFinding(true, "yes"), 1).caseInputIdentity((ctx, row) -> null));
		var bothMissing = run(
				builder("also-missing", ctx -> nativeFinding(true, "yes"), 1).caseInputIdentity((ctx, row) -> null));
		var asymmetric = run(builder("digest", ctx -> nativeFinding(true, "yes"), 1).caseInputIdentity((ctx,
				row) -> new CaseInputIdentity("fixture", ctx.goal(), "v1", "a".repeat(64), "effective-case-input:v1")));
		for (var pair : List.of(List.of(known, missing), List.of(missing, bothMissing), List.of(known, asymmetric)))
			assertThatThrownBy(() -> new DefaultComparisonEngine(store).compare(pair.get(0).experimentResult(),
					pair.get(1).experimentResult()))
				.hasMessageContaining("UNKNOWN");
		assertThat(missing.agreementRate()).isEqualTo(1.0);
	}

	@Test
	void E26_comparisonBindingsAreValidatedOnWriteLoadAndReport() throws Exception {
		var result = run(builder("binding", ctx -> nativeFinding(true, "yes"), 1));
		var row = result.experimentResult().items().getFirst();
		var detail = detail(result, 0);
		for (var mutation : List.of(Map.<String, Object>of("candidateSha256", "0".repeat(64)),
				Map.<String, Object>of("measureId", "other:v1"), Map.<String, Object>of("scorerRevision", "other"),
				Map.<String, Object>of("truth", JudgeTruth.proposition(false)))) {
			JudgeComparison comparison = copyRecord(detail.comparison(), mutation);
			JudgeExecutionDetail invalidDetail = copyRecord(detail, Map.of("comparison", comparison));
			var invalid = copyRecord(result.experimentResult(),
					Map.of("items", List.of(row.toBuilder().executionDetail(invalidDetail).build())));
			assertThatThrownBy(() -> mapper.writeValueAsString(invalid)).isInstanceOf(Exception.class);
			assertThatThrownBy(() -> store.save(invalid)).isInstanceOf(Exception.class);
			assertThatThrownBy(() -> JudgeExperimentResult.from(invalid)).isInstanceOf(Exception.class);
			assertThatThrownBy(() -> new DefaultComparisonEngine(store).summarize(invalid))
				.isInstanceOf(Exception.class);
			ObjectNode disk = mapper.valueToTree(result.experimentResult());
			((ObjectNode) disk.at("/items/0/executionDetail")).set("comparison", mapper.valueToTree(comparison));
			assertThatThrownBy(() -> mapper.treeToValue(disk, ExperimentResult.class)).isInstanceOf(Exception.class);
		}
	}

	@Test
	void E27_observationFailurePreservesCandidateTimingAndIndependentScore() throws Exception {
		var result = run(builder("observation", ctx -> nativeFinding(true, "yes"), 1)
			.observationExtractor((row, candidate, failures, basic) -> {
				throw new IllegalStateException("trace unavailable");
			}));
		assertThat(detail(result, 0).invocationState()).isEqualTo("RETURNED");
		assertThat(result.experimentResult().items().getFirst().success()).isTrue();
		assertThat(result.agreementRate()).isEqualTo(1.0);
		assertThat(detail(result, 0).observation().elapsedNanos()).isNotNegative();
		assertThat(result.experimentResult().totalTokens()).isNull();
		assertThat(detail(result, 0).failures()).anySatisfy(f -> assertThat(f.phase()).isEqualTo("OBSERVATION"));
	}

	void assertInvalidSavedMutations(ExperimentResult result, ObjectNode original, List<Consumer<ObjectNode>> mutations)
			throws Exception {
		Path file = root.resolve("results/exhibits/" + result.experimentId() + ".json");
		for (var mutation : mutations) {
			ObjectNode invalid = original.deepCopy();
			mutation.accept(invalid);
			String text = mapper.writeValueAsString(invalid);
			Files.writeString(file, text);
			assertThatThrownBy(() -> store.load(result.experimentId())).isInstanceOf(Exception.class);
			assertThat(Files.readString(file)).isEqualTo(text);
			assertThatThrownBy(() -> store.listByName("exhibits")).isInstanceOf(Exception.class);
			assertThatThrownBy(() -> store.mostRecent("exhibits")).isInstanceOf(Exception.class);
		}
		Files.writeString(file, mapper.writeValueAsString(original));
	}

	@SuppressWarnings("unchecked")
	static <T extends Record> T copyRecord(T value, Map<String, Object> changes) throws Exception {
		var components = value.getClass().getRecordComponents();
		Class<?>[] types = new Class<?>[components.length];
		Object[] values = new Object[components.length];
		for (int i = 0; i < components.length; i++) {
			var c = components[i];
			types[i] = c.getType();
			values[i] = changes.containsKey(c.getName()) ? changes.get(c.getName()) : c.getAccessor().invoke(value);
		}
		return (T) value.getClass().getDeclaredConstructor(types).newInstance(values);
	}

}
