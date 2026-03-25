package io.github.markpollack.experiment.store;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RunSessionTest {

	private final ObjectMapper mapper = ResultObjectMapper.create();

	@Test
	void roundTripsRunSession() throws Exception {
		RunSession original = new RunSession("full-suite-2026-03-03", "code-coverage-experiment",
				Instant.parse("2026-03-03T07:16:00Z"), Instant.parse("2026-03-03T08:55:00Z"),
				RunSessionStatus.COMPLETED,
				List.of(new VariantEntry("control", "05aa20bb", "control.json", 1.0, 5, 4.50, 120000)),
				Map.of("model", "claude-sonnet-4-6"));

		String json = mapper.writeValueAsString(original);
		RunSession restored = mapper.readValue(json, RunSession.class);

		assertThat(restored.sessionName()).isEqualTo("full-suite-2026-03-03");
		assertThat(restored.experimentName()).isEqualTo("code-coverage-experiment");
		assertThat(restored.createdAt()).isEqualTo(Instant.parse("2026-03-03T07:16:00Z"));
		assertThat(restored.completedAt()).isEqualTo(Instant.parse("2026-03-03T08:55:00Z"));
		assertThat(restored.status()).isEqualTo(RunSessionStatus.COMPLETED);
		assertThat(restored.variants()).hasSize(1);
		assertThat(restored.metadata()).containsEntry("model", "claude-sonnet-4-6");
	}

	@Test
	void roundTripsRunSessionWithNullCompletedAt() throws Exception {
		RunSession original = new RunSession("in-progress", "test-experiment", Instant.parse("2026-03-03T10:00:00Z"),
				null, RunSessionStatus.RUNNING, List.of(), Map.of());

		String json = mapper.writeValueAsString(original);
		RunSession restored = mapper.readValue(json, RunSession.class);

		assertThat(restored.completedAt()).isNull();
		assertThat(restored.status()).isEqualTo(RunSessionStatus.RUNNING);
		assertThat(restored.variants()).isEmpty();
	}

	@Test
	void roundTripsVariantEntry() throws Exception {
		VariantEntry original = new VariantEntry("variant-a", "4f25dfd2", "variant-a.json", 0.8, 10, 9.25, 300000);

		String json = mapper.writeValueAsString(original);
		VariantEntry restored = mapper.readValue(json, VariantEntry.class);

		assertThat(restored.variantName()).isEqualTo("variant-a");
		assertThat(restored.experimentId()).isEqualTo("4f25dfd2");
		assertThat(restored.resultFile()).isEqualTo("variant-a.json");
		assertThat(restored.passRate()).isEqualTo(0.8);
		assertThat(restored.itemCount()).isEqualTo(10);
		assertThat(restored.costUsd()).isEqualTo(9.25);
		assertThat(restored.durationMs()).isEqualTo(300000);
	}

	@Test
	void roundTripsSessionIndex() throws Exception {
		SessionIndex original = new SessionIndex("code-coverage-experiment",
				List.of(new SessionIndex.SessionIndexEntry("full-suite-2026-03-03",
						Instant.parse("2026-03-03T07:16:00Z"), RunSessionStatus.COMPLETED, 4,
						"sessions/full-suite-2026-03-03/")));

		String json = mapper.writeValueAsString(original);
		SessionIndex restored = mapper.readValue(json, SessionIndex.class);

		assertThat(restored.experimentName()).isEqualTo("code-coverage-experiment");
		assertThat(restored.entries()).hasSize(1);

		SessionIndex.SessionIndexEntry entry = restored.entries().get(0);
		assertThat(entry.sessionName()).isEqualTo("full-suite-2026-03-03");
		assertThat(entry.createdAt()).isEqualTo(Instant.parse("2026-03-03T07:16:00Z"));
		assertThat(entry.status()).isEqualTo(RunSessionStatus.COMPLETED);
		assertThat(entry.variantCount()).isEqualTo(4);
		assertThat(entry.path()).isEqualTo("sessions/full-suite-2026-03-03/");
	}

	@Test
	void enumSerializesAsString() throws Exception {
		String json = mapper.writeValueAsString(RunSessionStatus.RUNNING);
		assertThat(json).isEqualTo("\"RUNNING\"");

		RunSessionStatus restored = mapper.readValue(json, RunSessionStatus.class);
		assertThat(restored).isEqualTo(RunSessionStatus.RUNNING);
	}

	@Test
	void runSessionVariantsAreDefensivelyCopied() {
		var mutableList = new java.util.ArrayList<>(
				List.of(new VariantEntry("v1", "id1", "v1.json", 1.0, 5, 1.0, 1000)));

		RunSession session = new RunSession("test", "exp", Instant.now(), null, RunSessionStatus.RUNNING, mutableList,
				Map.of());

		mutableList.add(new VariantEntry("v2", "id2", "v2.json", 0.5, 3, 2.0, 2000));
		assertThat(session.variants()).hasSize(1);
	}

}
