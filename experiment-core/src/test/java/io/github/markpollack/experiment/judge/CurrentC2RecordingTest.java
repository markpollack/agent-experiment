package io.github.markpollack.experiment.judge;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.markpollack.experiment.dataset.DatasetItem;
import io.github.markpollack.experiment.recording.*;
import io.github.markpollack.experiment.result.*;
import io.github.markpollack.experiment.store.*;
import io.github.markpollack.experiment.attestation.*;
import io.github.markpollack.judge.judgment.*;
import io.github.markpollack.judge.provenance.Invocation;
import io.github.markpollack.judge.requirement.Requirement;
import io.github.markpollack.judge.verdict.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Synthetic current-protocol controls; no CLI or external inference. */
class CurrentC2RecordingTest {

	@TempDir
	Path root;
	static final String COST_SOURCE = "vercel-gateway-reported:v1:/provider_metadata/gateway/cost";

	static Invocation owner(String id, Map<String, Object> usage) {
		return new Invocation(id, "jev-systemone:0.2.0", true, "fixture", 2, Map.of("usage", usage), List.of());
	}

	static Map<String, Object> usage(Double cost) {
		var value = new LinkedHashMap<String, Object>();
		value.put("inputTokens", 10);
		value.put("outputTokens", 2);
		if (cost != null) {
			value.put("cost", cost);
			value.put("currency", "USD");
			value.put("costSource", COST_SOURCE);
		}
		return value;
	}

	static Judgment answer(Invocation owner) {
		return new Judgment(JudgmentStatus.FAIL, new Finding(new BooleanFinding(false), null, null), null, null, null,
				"fixture negative", List.of(), null, Map.of("usage", Map.of("cost", 999)), null, List.of(owner),
				List.of());
	}

	static InvocationObservation basic() {
		return new InvocationObservation("orchestrated", "FAKE", null, null, null, null, null, null, List.of(),
				List.of());
	}

	JudgeExperimentResult run(String id, Judgment answer, AtomicInteger calls) {
		return JudgeExperiment.builder()
			.name("current-c2")
			.runId(id)
			.candidateId("fixture")
			.captureMode("FAKE")
			.items(List.of(new DatasetItem("case", "case", "fixture", "test", "A", false, List.of(), List.of(),
					"active", root, null, null)))
			.candidate(item -> () -> {
				calls.incrementAndGet();
				return answer;
			})
			.caseInputIdentity(
					(item, row) -> new CaseInputIdentity("fixture", item.id(), "v1", null, "effective-case-input:v1"))
			.truth(item -> JudgeTruth.status(answer.status().name()))
			.scorer(JudgeScorers.exactVerdictMatch())
			.resultStore(new FileSystemResultStore(root))
			.build()
			.run();
	}

	@Test
	void zeroAndNonzeroCostRetainExactSourceAndReopenWithoutExecution() {
		for (double cost : List.of(0.0, 1.25)) {
			var calls = new AtomicInteger();
			var result = run("cost-" + cost, answer(owner("native-" + cost, usage(cost))), calls);
			var store = new FileSystemResultStore(root);
			var read = store.load(result.experimentResult().experimentId()).orElseThrow();
			assertThat(read.totalCostUsd()).isEqualTo(cost);
			assertThat(read.totalTokens()).isEqualTo(12L);
			var fact = ((JudgeExecutionDetail) read.items().getFirst().executionDetail()).observation()
				.usage()
				.getFirst();
			assertThat(fact.source()).isEqualTo(COST_SOURCE);
			assertThat(fact.currency()).isEqualTo("USD");
			assertThat(fact.priceRuleId()).isNull();
			assertThat(fact.ownerSha256()).matches("[0-9a-f]{64}");
			assertThat(store.listByName("current-c2")).contains(read);
			assertThat(store.mostRecent("current-c2")).isPresent();
			assertThat(calls).hasValue(1);
		}
	}

	@Test
	void invalidAbsentCurrencyAndSourceNeverBecomeMeasuredCharges() {
		var variants = new ArrayList<Map<String, Object>>();
		variants.add(usage(null));
		for (var entry : List.of(Map.entry("cost", -1.0), Map.entry("currency", "EUR"),
				Map.entry("costSource", "invented"))) {
			var value = usage(1.0);
			value.put(entry.getKey(), entry.getValue());
			variants.add(value);
		}
		int id = 0;
		for (var value : variants) {
			var result = run("invalid-" + (id++), answer(owner("owner-" + id, value)), new AtomicInteger())
				.experimentResult();
			assertThat(result.totalCostUsd()).isNull();
			assertThat(result.measurements().costUsd().measured()).isZero();
		}
		assertThatThrownBy(() -> owner("nonfinite", usage(Double.NaN))).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void costWithoutTokensAndReturnedErrorRemainChargeable() {
		var value = usage(2.5);
		value.remove("inputTokens");
		value.remove("outputTokens");
		var error = Judgment.error("returned error").withInvocation(owner("error-owner", value));
		var result = run("error-cost", error, new AtomicInteger()).experimentResult();
		assertThat(result.totalCostUsd()).isEqualTo(2.5);
		assertThat(result.totalTokens()).isNull();
		assertThat(result.counts().unattestable()).isEqualTo(1);
		assertThat(result.counts().nonPasses()).isZero();
		assertThat(ItemAccounting.instrumentHealth(result.items().getFirst())).isEqualTo(InstrumentHealth.UNKNOWN);
	}

	@Test
	void unknownProtocolDoesNotAcquireJevMeasurements() {
		var unsupported = new Invocation("native", "unrecognized:v1", true, "fixture", 1, Map.of("usage", usage(1.0)),
				List.of());
		var unknown = JevObservations.extract(RecordedJudgment.from(answer(unsupported)), basic());
		assertThat(unknown.usage()).hasSize(1);
		assertThat(MeasurementSummary.of(List.of(unknown)).costUsd().total()).isNull();
	}

	@Test
	void wholeRosterRootOwnersAndRefusedOriginalsAreChargedOnceAfterActualStore() {
		var req = Requirement.text("requirement", "v1", "fixture requirement");
		var nativeOwner = owner("shared", usage(1.75));
		var judgment = Judgment.pass("child").forRequirement(req).withInvocationIds(List.of(nativeOwner.id()));
		var child = Verdict.single("child", judgment).forRequirement(req);
		var attempt = new CompositeAttempt(req.id(), CompositeRelation.ROSTER_ITEM, null, AttemptDisposition.USED, null,
				child, null);
		var whole = new Verdict.Builder().judgment(Judgment.pass("roster"))
			.provenance(new VerdictProvenance(VerdictProvenanceKind.ROSTER, null, null))
			.roster(List.of(req))
			.declaredCardinality(1)
			.compositeAttempts(List.of(attempt))
			.invocations(List.of(nativeOwner))
			.build()
			.requireUsable();
		var row = ItemResult.builder().itemId("case").itemSlug("case").verdict(whole).build();
		var result = ExperimentResult.builder()
			.experimentId("roster")
			.experimentName("current-roster")
			.timestamp(Instant.now())
			.items(List.of(row))
			.counts(ItemAccounting.count(List.of(row)))
			.build();
		var store = new FileSystemResultStore(root);
		store.save(result);
		var stored = store.load("roster").orElseThrow().items().getFirst().verdict();
		var extracted = JevObservations.extract(stored, basic());
		assertThat(extracted.usage()).hasSize(1);
		assertThat(MeasurementSummary.of(List.of(extracted)).costUsd().total()).isEqualTo(1.75);
		var wrong = Requirement.text("other", "v1", "other requirement");
		var original = answer(nativeOwner).forRequirement(wrong);
		var refused = Judgment.refuse(original, req, nativeOwner);
		var refusedResult = run("refused", refused, new AtomicInteger()).experimentResult();
		var reopened = new FileSystemResultStore(root).load(refusedResult.experimentId()).orElseThrow();
		var refusedDocument = ((JudgeExecutionDetail) reopened.items().getFirst().executionDetail())
			.candidateJudgment();
		assertThat(refusedDocument.document().node().has("refusedReturn")).isTrue();
		var retained = JevObservations.extract(refusedDocument, basic());
		assertThat(retained.usage()).hasSize(1);
		assertThat(MeasurementSummary.of(List.of(retained)).costUsd().total()).isEqualTo(1.75);
	}

	@Test
	void sharedOwnerDeduplicatesAndConflictingPortableOwnersFailClosed() {
		var first = JevObservations.extract(RecordedJudgment.from(answer(owner("same", usage(1.0)))), basic());
		assertThat(MeasurementSummary.of(List.of(first, first)).costUsd().total()).isEqualTo(1.0);
		var changed = usage(1.0);
		changed.put("unrelatedNativeFact", "changed");
		var second = JevObservations.extract(RecordedJudgment.from(answer(owner("same", changed))), basic());
		assertThat(MeasurementSummary.of(List.of(first, second)).costUsd().total()).isNull();
		assertThat(MeasurementSummary.of(List.of(first, second)).costUsd().conflicted()).isEqualTo(1);
	}

	@Test
	void costTokenSourceAndOwnerTamperingCannotHideBehindConsistentTotals() throws Exception {
		var calls = new AtomicInteger();
		var result = run("tamper", answer(owner("immutable", usage(1.0))), calls).experimentResult();
		var mapper = RecordingVectorsTest.mapper();
		var original = (ObjectNode) mapper.valueToTree(result);
		Path file = root.resolve("current-c2/tamper.json");
		byte[] bytes = Files.readAllBytes(file);
		for (String field : List.of("cost", "inputTokens", "source", "ownerSha256")) {
			ObjectNode wire = original.deepCopy();
			ObjectNode observed = (ObjectNode) wire.at("/items/0/executionDetail/observation/usage/0");
			if (field.equals("cost")) {
				observed.put(field, 777.0);
				((ObjectNode) wire.at("/items/0")).put("costUsd", 777.0);
				wire.put("totalCostUsd", 777.0);
				((ObjectNode) wire.at("/measurements/costUsd")).put("total", 777.0).put("knownSubtotal", 777.0);
			}
			else if (field.equals("inputTokens")) {
				observed.put(field, 999);
				((ObjectNode) wire.at("/items/0")).put("totalTokens", 1001);
				wire.put("totalTokens", 1001);
				((ObjectNode) wire.at("/measurements/tokens")).put("total", 1001).put("knownSubtotal", 1001);
			}
			else
				observed.put(field, field.equals("source") ? "invented" : "a".repeat(64));
			Files.writeString(file, wire.toString());
			var store = new FileSystemResultStore(root);
			assertThatThrownBy(() -> store.load("tamper")).hasStackTraceContaining("retained native owner");
			assertThatThrownBy(() -> store.listByName("current-c2")).hasStackTraceContaining("retained native owner");
			assertThatThrownBy(() -> store.mostRecent("current-c2")).hasStackTraceContaining("retained native owner");
		}
		Files.write(file, bytes);
		assertThat(new FileSystemResultStore(root).load("tamper")).contains(result);
		assertThat(calls).hasValue(1);
	}

	@Test
	void mixedSupportedAndUnknownOwnersPreserveKnownSubtotalAndUnknownWholeTotal() {
		var known = owner("known", usage(1.25));
		var unknown = new Invocation("other", "unsupported:v1", true, "other", 1, Map.of("usage", usage(50.0)),
				List.of());
		var value = answer(known).withInvocations(List.of(known, unknown));
		var run = run("mixed-protocol", value, new AtomicInteger()).experimentResult();
		var reopened = new FileSystemResultStore(root).load(run.experimentId()).orElseThrow();
		assertThat(reopened.totalCostUsd()).isNull();
		assertThat(reopened.totalTokens()).isNull();
		assertThat(reopened.measurements().costUsd().knownSubtotal()).isEqualTo(1.25);
		assertThat(reopened.measurements().costUsd().unknown()).isEqualTo(1);
		assertThat(reopened.measurements().tokens().knownSubtotal()).isEqualTo(12L);
		assertThat(((JudgeExecutionDetail) reopened.items().getFirst().executionDetail()).observation().usage())
			.hasSize(2);
	}

	@Test
	void extraProviderChargeRowsRefuseForKnownAndEmptyNativeOwners() throws Exception {
		for (boolean known : List.of(false, true)) {
			String id = "extra-" + known;
			var result = run(id, known ? answer(owner("real", usage(1.0))) : Judgment.pass("no native owner"),
					new AtomicInteger())
				.experimentResult();
			var detail = (JudgeExecutionDetail) result.items().getFirst().executionDetail();
			var rows = new ArrayList<>(detail.observation().usage());
			rows.add(new UsageObservation("plant", "other-provider", "unbound", "adapter-request", 5L, 7L,
					"input-plus-output:v1", 77.0, "USD", null));
			var summary = MeasurementSummary.of(List.of(detail.observation().withUsage(rows)));
			var mapper = RecordingVectorsTest.mapper();
			ObjectNode wire = (ObjectNode) mapper.valueToTree(result);
			((ObjectNode) wire.at("/items/0/executionDetail/observation")).set("usage", mapper.valueToTree(rows));
			((ObjectNode) wire.at("/items/0")).put("costUsd", summary.costUsd().total())
				.put("totalTokens", summary.tokens().total());
			wire.put("totalCostUsd", summary.costUsd().total()).put("totalTokens", summary.tokens().total());
			wire.set("measurements", mapper.valueToTree(summary));
			Files.writeString(root.resolve("current-c2/" + id + ".json"), wire.toString());
			var store = new FileSystemResultStore(root);
			assertThatThrownBy(() -> store.load(id)).hasStackTraceContaining("retained native owner");
			assertThatThrownBy(() -> store.listByName("current-c2")).hasStackTraceContaining("retained native owner");
			assertThatThrownBy(() -> store.mostRecent("current-c2")).hasStackTraceContaining("retained native owner");
			Files.writeString(root.resolve("current-c2/" + id + ".json"), mapper.writeValueAsString(result));
		}
	}

	@Test
	void inconclusiveProducerCasesRemainUnknownAfterStore() {
		var requirement = Requirement.text("unknown-requirement", "v1", "fixture");
		var child = Verdict.single("error", Judgment.error("error").forRequirement(requirement))
			.forRequirement(requirement);
		var rootVerdict = new Verdict.Builder().judgment(Judgment.abstain("incomplete"))
			.provenance(new VerdictProvenance(VerdictProvenanceKind.ROSTER, null, null))
			.roster(List.of(requirement))
			.declaredCardinality(1)
			.compositeAttempts(List.of(new CompositeAttempt(requirement.id(), CompositeRelation.ROSTER_ITEM, null,
					AttemptDisposition.USED, null, child, null)))
			.build();
		int i = 0;
		for (var verdict : List.of(Verdict.single("abstain", Judgment.abstain("unknown")),
				Verdict.single("error", Judgment.error("error")), rootVerdict)) {
			var row = ItemResult.builder().itemId("item").itemSlug("item").verdict(verdict).build();
			var result = ExperimentResult.builder()
				.experimentId("unknown-" + (i++))
				.experimentName("unknown")
				.timestamp(Instant.now())
				.items(List.of(row))
				.counts(ItemAccounting.count(List.of(row)))
				.build();
			var store = new FileSystemResultStore(root);
			store.save(result);
			var reopened = store.load(result.experimentId()).orElseThrow();
			assertThat(reopened.counts().unattestable()).isEqualTo(1);
			assertThat(reopened.counts().nonPasses()).isZero();
			assertThat(reopened.counts().excluded()).isZero();
			assertThat(ItemAccounting.instrumentHealth(reopened.items().getFirst()))
				.isEqualTo(InstrumentHealth.UNKNOWN);
		}
	}

	@Test
	void sharedAndConflictingOwnersAcrossActualStoredRowsKeepPopulationAndCoverage() {
		for (boolean conflict : List.of(false, true)) {
			String id = "cross-row-" + conflict;
			var cases = new ArrayList<DatasetItem>();
			for (int i = 0; i < 2; i++)
				cases.add(new DatasetItem("case-" + i, "case-" + i, "fixture", "test", "A", false, List.of(), List.of(),
						"active", root, null, null));
			var run = JudgeExperiment.builder()
				.name("cross-row")
				.runId(id)
				.candidateId("fixture")
				.captureMode("FAKE")
				.items(cases)
				.candidate(item -> () -> {
					var facts = usage(1.0);
					if (conflict && item.id().endsWith("1"))
						facts.put("otherNativeFact", "changed");
					return answer(owner("shared", facts));
				})
				.caseInputIdentity((item, row) -> new CaseInputIdentity("fixture", item.id(), "v1", null,
						"effective-case-input:v1"))
				.truth(item -> JudgeTruth.status("FAIL"))
				.scorer(JudgeScorers.exactVerdictMatch())
				.resultStore(new FileSystemResultStore(root))
				.build()
				.run();
			var stored = new FileSystemResultStore(root).load(id).orElseThrow();
			assertThat(stored.items()).hasSize(2);
			if (conflict) {
				assertThat(stored.totalCostUsd()).isNull();
				assertThat(stored.measurements().costUsd().conflicted()).isEqualTo(1);
			}
			else {
				assertThat(stored.totalCostUsd()).isEqualTo(1.0);
				assertThat(stored.totalTokens()).isEqualTo(12L);
				assertThat(stored.measurements().costUsd().measured()).isEqualTo(1);
			}
			assertThat(stored.judgeRun().countRule()).isEqualTo("experiment-v6-conclusion:v1");
		}
	}

}
