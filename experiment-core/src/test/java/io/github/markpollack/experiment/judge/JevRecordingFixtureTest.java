package io.github.markpollack.experiment.judge;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import io.github.markpollack.experiment.dataset.DatasetItem;
import io.github.markpollack.experiment.recording.*;
import io.github.markpollack.experiment.store.*;
import io.github.markpollack.judge.context.JudgmentContext;
import io.github.markpollack.judge.result.*;
import io.github.markpollack.judge.jev.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/**
 * Real pinned Jev adapter, fixed response, loopback-only HTTP, caller-owned bounded
 * capture.
 */
class JevRecordingFixtureTest {

	@Test
	void E13_realAdapterRootUsageTraceAndRepeatedChecks(@TempDir Path directory) throws Exception {
		byte[] response = getClass().getResourceAsStream("/recording/jev-noul-valid.json").readAllBytes();
		AtomicInteger sends = new AtomicInteger();
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor();
				var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
			server.setExecutor(executor);
			server.createContext("/v1/systemone", exchange -> {
				sends.incrementAndGet();
				exchange.getRequestBody().readAllBytes();
				exchange.getResponseHeaders().add("x-typesafe-request-id", "fixture-request-1");
				exchange.sendResponseHeaders(200, response.length);
				exchange.getResponseBody().write(response);
				exchange.close();
			});
			server.start();
			ConcurrentMap<String, byte[]> bytes = new ConcurrentHashMap<>();
			ConcurrentMap<String, ArtifactRef> references = new ConcurrentHashMap<>();
			ArtifactCapture capture = (kind, content) -> {
				if (content.length > 32000 || bytes.size() >= 16)
					throw new IllegalStateException("Fixture capture bound");
				ArtifactRef ref = ArtifactRef.ofBytes("fixture:" + kind, content, null);
				bytes.put(ref.id(), content.clone());
				references.put(kind, ref);
				return ref;
			};
			String goal = "The evidence must meet the requirement.",
					evidence = "The requirement is explicitly violated.";
			ArtifactRef bundle = capture.retain("bundle", evidence.getBytes(StandardCharsets.UTF_8));
			ArtifactRef manifest = capture.retain("manifest",
					"fixture-selected-evidence:v1".getBytes(StandardCharsets.UTF_8));
			var selected = new JevEvidence(evidence, bundle, manifest,
					ProducerDocument.hash(goal.getBytes(StandardCharsets.UTF_8)), true);
			var judge = new JevJudge("fixture-key", "jev-1.13.0",
					URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/systemone"),
					Duration.ofSeconds(3), 16000, 32000,
					new JevQuestion.Noul("Evaluate state.requirement using only state.evidence.", "fixture:v1", true),
					http, capture);
			var store = new FileSystemResultStore(directory);
			var result = JudgeExperiment.builder()
				.name("jev-fixture")
				.runId("fake-jev")
				.candidateId("fixture-noul")
				.captureMode("FIXTURE")
				.items(List.of(new DatasetItem("case", "case", goal, "fixture", "A", false, List.of(), List.of(),
						"active", directory, null, null)))
				.input(item -> JudgmentContext.builder().goal(goal).metadata(JevEvidence.CONTEXT_KEY, selected).build())
				.candidate(ctx -> {
					Judgment actual = judge.judge(ctx);
					return actual.toBuilder().check(new Check("repeat", actual)).build();
				})
				.truth(item -> JudgeTruth.proposition(false))
				.scorer(JudgeScorers.propositionMatch())
				.observationExtractor((row, candidate, failures, basic) -> JevObservations.withTransportTrace(basic,
						references.get("trace"), bytes.get(references.get("trace").id())))
				.resultStore(store)
				.build()
				.run();
			var loaded = JudgeExperimentResult.from(store.load("fake-jev").orElseThrow());
			var detail = (JudgeExecutionDetail) loaded.experimentResult().items().getFirst().executionDetail();
			assertThat(sends).hasValue(1);
			assertThat(loaded.agreementRate()).isEqualTo(1.0);
			assertThat(loaded.experimentResult().totalTokens()).isEqualTo(132L);
			assertThat(loaded.experimentResult().totalCostUsd()).isNull();
			assertThat(detail.observation().usage()).hasSize(1);
			assertThat(detail.observation().transportAttempts()).isEqualTo(1);
			assertThat(detail.observation().requestId()).isEqualTo("fixture-request-1");
			assertThat(detail.observation().transportElapsedNanos()).isPositive();
			assertThat(detail.observation().elapsedNanos())
				.isGreaterThanOrEqualTo(detail.observation().transportElapsedNanos());
			assertThat(detail.observation().artifacts()).contains(references.get("trace"), references.get("response"));
			assertThat(detail.candidateJudgment()
				.document()
				.node()
				.at("/checks/0/judgment/metadata/usage/inputTokens")
				.intValue()).isEqualTo(120);
			assertThat(store.listByName("jev-fixture")).hasSize(1);
			assertThat(store.mostRecent("jev-fixture")).isPresent();
			for (var entry : bytes.entrySet())
				Files.write(directory.resolve(entry.getKey().replace(':', '-') + ".bin"), entry.getValue());
			try (var paths = Files.walk(directory)) {
				for (Path path : paths.filter(Files::isRegularFile).toList())
					RecordingVectorsTest.preserveEvidence(path, "jev-fixture/" + directory.relativize(path));
			}
		}
		finally {
			server.stop(0);
		}
	}

}
