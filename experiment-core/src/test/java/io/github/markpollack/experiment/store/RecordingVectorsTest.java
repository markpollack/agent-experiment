package io.github.markpollack.experiment.store;

import java.nio.file.*;
import java.util.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.markpollack.experiment.result.*;
import io.github.markpollack.experiment.recording.*;
import io.github.markpollack.experiment.attestation.ItemAccounting;
import io.github.markpollack.judge.jury.Verdict;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

/** Fixed producer bytes plus hand-authored facts, exercised through the real store. */
public class RecordingVectorsTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final String VECTOR_SHA = "db01735812fc729b8528a893d6a97182659ebe27e16379c91b3ea14ec0c83abe";

	@TempDir
	Path root;

	record Vector(String id, String source, JsonNode expected) {
		@Override
		public String toString() {
			return id;
		}
	}

	static List<Vector> vectors() throws Exception {
		byte[] bytes = RecordingVectorsTest.class.getResourceAsStream("/recording/portable-results-v2.json")
			.readAllBytes();
		assertThat(ProducerDocument.hash(bytes)).isEqualTo(VECTOR_SHA);
		String text = new String(bytes, StandardCharsets.UTF_8);
		JsonNode expected = JSON.readTree(text);
		List<Vector> result = new ArrayList<>();
		try (JsonParser parser = JSON.createParser(text)) {
			assertThat(parser.nextToken()).isEqualTo(JsonToken.START_ARRAY);
			int index = 0;
			while (parser.nextToken() != JsonToken.END_ARRAY) {
				String source = null;
				while (parser.nextToken() != JsonToken.END_OBJECT) {
					String field = parser.currentName();
					parser.nextToken();
					if (field.equals("verdict")) {
						int start = (int) parser.currentTokenLocation().getCharOffset();
						parser.skipChildren();
						source = text.substring(start, (int) parser.currentLocation().getCharOffset());
					}
					else
						parser.skipChildren();
				}
				JsonNode oracle = expected.get(index++);
				result.add(new Vector(oracle.path("id").asText(), source, oracle));
			}
		}
		assertThat(result).hasSize(15);
		return result;
	}

	@ParameterizedTest(name = "fixed source {0}")
	@MethodSource("vectors")
	void everyFixedVectorSurvivesMapperStoreAndDiscovery(Vector vector) throws Exception {
		FileSystemResultStore store = new FileSystemResultStore(root);
		RecordedVerdict verdict = RecordedVerdict.fromProducerJson(vector.source());
		ExperimentResult result = subject(vector.id(), verdict);
		store.save(result);
		var loaded = store.load(vector.id()).orElseThrow();
		assertThat(loaded.items().getFirst().verdict().producerJson()).isEqualTo(vector.source());
		assertThat(loaded.items().getFirst().verdict().sha256())
			.isEqualTo(ProducerDocument.hash(vector.source().getBytes(StandardCharsets.UTF_8)));
		var reading = loaded.items().getFirst().interpretation();
		assertThat(reading.reading() == null ? null : reading.reading().name())
			.isEqualTo(vector.expected().path("reading").isNull() ? null : vector.expected().path("reading").asText());
		assertThat(reading.readingSupport().name()).isEqualTo(vector.expected().path("readingSupport").asText());
		JsonNode interpreted = JSON.valueToTree(reading);
		for (var expected : vector.expected().path("expectedPaths").properties())
			assertThat(interpreted.at(expected.getKey())).as(expected.getKey()).isEqualTo(expected.getValue());
		assertThat(store.listByName("vectors")).containsExactly(loaded);
		assertThat(store.mostRecent("vectors")).contains(loaded);
		assertThat(loaded.items().getFirst().interpretationSnapshot())
			.isEqualTo(result.items().getFirst().interpretationSnapshot());
		io.github.markpollack.experiment.attestation.RunAttestation.of(loaded);
		if (vector.id().equals("contained-invocation")) {
			assertThat(loaded.items().getFirst().passed()).isFalse();
			assertThat(io.github.markpollack.experiment.attestation.ItemAccounting
				.instrumentHealth(loaded.items().getFirst())
				.name()).isEqualTo("FAILED");
			assertThat(interpreted.at("/root/judges/0/execution").asText()).isEqualTo("CONTAINED_FAILURE");
		}
		if (vector.id().equals("valid-returned-identical-error"))
			assertThat(interpreted.at("/root/judges/0/execution").asText()).isEqualTo("RETURNED");
		if (vector.id().equals("negative-ordinal-identity"))
			assertRichFacts(ProducerDocument.parse(loaded.items().getFirst().verdict().producerJson()));
		preserveEvidence(root.resolve("vectors").resolve(vector.id() + ".json"), "vectors/" + vector.id() + ".json");
	}

	@ParameterizedTest(name = "live factory {0}")
	@MethodSource("liveVectors")
	void constructibleVectorsAlsoUseActualLiveFactory(Vector vector) throws Exception {
		Verdict live = JSON.readValue(vector.source(), Verdict.class);
		RecordedVerdict recorded = RecordedVerdict.from(live);
		FileSystemResultStore store = new FileSystemResultStore(root);
		store.save(subject(vector.id(), recorded));
		var loaded = store.load(vector.id()).orElseThrow();
		assertThat(ProducerDocument.parse(loaded.items().getFirst().verdict().producerJson()))
			.isEqualTo(ProducerDocument.parse(vector.source()));
		assertThat(loaded.items().getFirst().interpretation().reading().name())
			.isEqualTo(vector.expected().path("reading").asText());
		if (vector.id().equals("negative-ordinal-identity"))
			assertRichFacts(ProducerDocument.parse(loaded.items().getFirst().verdict().producerJson()));
	}

	static List<Vector> liveVectors() throws Exception {
		return vectors().subList(0, 9);
	}

	@Test
	void independentFactsCatchSharedWriterReaderOmissions() throws Exception {
		JsonNode source = ProducerDocument.parse(vectors().getFirst().source());
		assertRichFacts(source);
		for (String pointer : List.of("/aggregated/certainty/target", "/aggregated/assessment/numeric/qualityDirection",
				"/aggregated/distribution/masses", "/aggregated/checks")) {
			ObjectNode copy = source.deepCopy();
			int slash = pointer.lastIndexOf('/');
			((ObjectNode) copy.at(pointer.substring(0, slash))).remove(pointer.substring(slash + 1));
			assertThatThrownBy(() -> assertRichFacts(copy)).isInstanceOf(AssertionError.class);
		}
	}

	public static ObjectMapper mapper() {
		return ResultObjectMapper.create();
	}

	public static String vectorSource(String id) throws Exception {
		return vectors().stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow().source();
	}

	static void assertRichFacts(JsonNode source) {
		assertThat(source.at("/aggregated/assessment/proposition/value").isBoolean()).isTrue();
		assertThat(source.at("/aggregated/assessment/proposition/value").booleanValue()).isFalse();
		assertThat(source.at("/aggregated/assessment/numeric/value").doubleValue()).isEqualTo(1.5);
		assertThat(source.at("/aggregated/assessment/numeric/levels"))
			.isEqualTo(ProducerDocument.parse("[\"none\",\"some\",\"many\"]"));
		assertThat(source.at("/aggregated/assessment/numeric/qualityDirection").asText()).isEqualTo("DECREASING");
		assertThat(source.at("/aggregated/certainty/metricId").asText()).isEqualTo("native-category:v1");
		assertThat(source.at("/aggregated/certainty/origin").asText()).isEqualTo("REPORTED");
		assertThat(source.at("/aggregated/certainty/target").asText()).isEqualTo("CATEGORY");
		assertThat(source.at("/aggregated/distribution/target").asText()).isEqualTo("CATEGORY");
		JsonNode masses = source.at("/aggregated/distribution/masses");
		assertThat(masses).hasSize(3);
		assertThat(masses.get(0).path("alternative").asText()).isEqualTo("satisfied");
		assertThat(masses.get(0).path("probability").doubleValue()).isEqualTo(0.1);
		assertThat(masses.get(1).path("alternative").asText()).isEqualTo("violated");
		assertThat(masses.get(1).path("probability").doubleValue()).isEqualTo(0.8);
		assertThat(masses.get(2).path("alternative").asText()).isEqualTo("unknown");
		assertThat(masses.get(2).path("probability").doubleValue()).isEqualTo(0.1);
		JsonNode checks = source.at("/aggregated/checks");
		assertThat(checks).hasSize(5);
		List<String> ids = new ArrayList<>();
		checks.forEach(v -> ids.add(v.path("id").asText()));
		assertThat(ids).containsExactly("PASS", "FAIL", "ABSTAIN", "NOT_APPLICABLE", "ERROR");
		assertThat(checks.get(3).path("judgment").has("assessment")).isFalse();
		assertThat(checks.get(4).path("judgment").path("producerStatus").asText()).isEqualTo("error");
		assertThat(source.at("/aggregated/reasoning").asText()).isEqualTo("native explanation");
		assertThat(source.at("/aggregated/provenance/calibrationClaims/0/id").asText()).isEqualTo("calibrated:v1");
		assertThat(source.at("/aggregated/policyApplication/action").asText()).isEqualTo("USE_ASSESSMENT");
		assertThat(source.at("/aggregated/metadata/native/tokens").intValue()).isEqualTo(7);
		assertThat(source.at("/aggregated/metadata/native/request").asText()).isEqualTo("r-1");
	}

	public static ExperimentResult subject(String id, RecordedVerdict verdict) {
		ItemResult item = ItemResult.builder().itemId("case").itemSlug("case").verdict(verdict).build();
		return ExperimentResult.builder()
			.experimentId(id)
			.experimentName("vectors")
			.timestamp(Instant.parse("2026-09-25T00:00:00Z"))
			.items(List.of(item))
			.counts(ItemAccounting.count(List.of(item)))
			.build();
	}

	public static void preserveEvidence(Path source, String name) throws Exception {
		String output = System.getProperty("experiment.test.evidence");
		if (output == null)
			return;
		Path path = Path.of(output).resolve(name);
		Files.createDirectories(path.getParent());
		Files.copy(source, path, StandardCopyOption.REPLACE_EXISTING);
	}

}
