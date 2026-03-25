package io.github.markpollack.experiment.store;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SweepTest {

	private final ObjectMapper mapper = ResultObjectMapper.create();

	@Test
	void roundTripsSweep() throws Exception {
		SweepVariantResolution resolved = new SweepVariantResolution("control", "session-1",
				Instant.parse("2026-03-04T10:00:00Z"), "abc123");
		SweepVariantResolution unresolved = SweepVariantResolution.unresolved("variant-a");

		Sweep original = new Sweep("stage5-full", "petclinic-migration", Instant.parse("2026-03-04T09:00:00Z"), null,
				SweepStatus.PARTIAL, List.of("control", "variant-a"), List.of(resolved, unresolved),
				List.of("session-1"), Map.of("model", "opus-4"));

		String json = mapper.writeValueAsString(original);
		Sweep restored = mapper.readValue(json, Sweep.class);

		assertThat(restored.sweepName()).isEqualTo("stage5-full");
		assertThat(restored.experimentName()).isEqualTo("petclinic-migration");
		assertThat(restored.createdAt()).isEqualTo(Instant.parse("2026-03-04T09:00:00Z"));
		assertThat(restored.completedAt()).isNull();
		assertThat(restored.status()).isEqualTo(SweepStatus.PARTIAL);
		assertThat(restored.expectedVariants()).containsExactly("control", "variant-a");
		assertThat(restored.resolutions()).hasSize(2);
		assertThat(restored.sessionHistory()).containsExactly("session-1");
		assertThat(restored.metadata()).containsEntry("model", "opus-4");
	}

	@Test
	void roundTripsCompletedSweep() throws Exception {
		Sweep original = new Sweep("done-sweep", "exp", Instant.parse("2026-03-04T09:00:00Z"),
				Instant.parse("2026-03-04T12:00:00Z"), SweepStatus.COMPLETED, List.of("control"),
				List.of(new SweepVariantResolution("control", "s1", Instant.parse("2026-03-04T10:00:00Z"), "abc")),
				List.of("s1"), Map.of());

		String json = mapper.writeValueAsString(original);
		Sweep restored = mapper.readValue(json, Sweep.class);

		assertThat(restored.completedAt()).isEqualTo(Instant.parse("2026-03-04T12:00:00Z"));
		assertThat(restored.status()).isEqualTo(SweepStatus.COMPLETED);
	}

	@Test
	void roundTripsSweepVariantResolution() throws Exception {
		SweepVariantResolution original = new SweepVariantResolution("variant-a", "session-2",
				Instant.parse("2026-03-04T11:00:00Z"), "def456");

		String json = mapper.writeValueAsString(original);
		SweepVariantResolution restored = mapper.readValue(json, SweepVariantResolution.class);

		assertThat(restored.variantName()).isEqualTo("variant-a");
		assertThat(restored.sessionName()).isEqualTo("session-2");
		assertThat(restored.resolvedAt()).isEqualTo(Instant.parse("2026-03-04T11:00:00Z"));
		assertThat(restored.gitCommit()).isEqualTo("def456");
	}

	@Test
	void roundTripsSweepIndex() throws Exception {
		SweepIndex original = new SweepIndex("petclinic-migration",
				List.of(new SweepIndex.SweepIndexEntry("stage5-full", Instant.parse("2026-03-04T09:00:00Z"),
						SweepStatus.PARTIAL, 3, 5, "sweeps/stage5-full/")));

		String json = mapper.writeValueAsString(original);
		SweepIndex restored = mapper.readValue(json, SweepIndex.class);

		assertThat(restored.experimentName()).isEqualTo("petclinic-migration");
		assertThat(restored.entries()).hasSize(1);

		SweepIndex.SweepIndexEntry entry = restored.entries().get(0);
		assertThat(entry.sweepName()).isEqualTo("stage5-full");
		assertThat(entry.status()).isEqualTo(SweepStatus.PARTIAL);
		assertThat(entry.resolvedCount()).isEqualTo(3);
		assertThat(entry.expectedCount()).isEqualTo(5);
		assertThat(entry.path()).isEqualTo("sweeps/stage5-full/");
	}

	@Test
	void unresolvedFactoryCreatesUnresolvedResolution() {
		SweepVariantResolution resolution = SweepVariantResolution.unresolved("variant-b");

		assertThat(resolution.variantName()).isEqualTo("variant-b");
		assertThat(resolution.sessionName()).isNull();
		assertThat(resolution.resolvedAt()).isNull();
		assertThat(resolution.gitCommit()).isNull();
		assertThat(resolution.isResolved()).isFalse();
	}

	@Test
	void resolvedResolutionIsResolved() {
		SweepVariantResolution resolution = new SweepVariantResolution("control", "session-1", Instant.now(), "abc123");

		assertThat(resolution.isResolved()).isTrue();
	}

	@Test
	void missingVariantsReturnsUnresolvedVariants() {
		Sweep sweep = new Sweep("test", "exp", Instant.now(), null, SweepStatus.PARTIAL,
				List.of("control", "variant-a", "variant-b"),
				List.of(new SweepVariantResolution("control", "s1", Instant.now(), null),
						SweepVariantResolution.unresolved("variant-a"), SweepVariantResolution.unresolved("variant-b")),
				List.of("s1"), Map.of());

		assertThat(sweep.missingVariants()).containsExactly("variant-a", "variant-b");
	}

	@Test
	void missingVariantsReturnsEmptyWhenAllResolved() {
		Sweep sweep = new Sweep("test", "exp", Instant.now(), null, SweepStatus.PARTIAL,
				List.of("control", "variant-a"),
				List.of(new SweepVariantResolution("control", "s1", Instant.now(), null),
						new SweepVariantResolution("variant-a", "s2", Instant.now(), null)),
				List.of("s1", "s2"), Map.of());

		assertThat(sweep.missingVariants()).isEmpty();
	}

	@Test
	void isCompleteWhenAllVariantsResolved() {
		Sweep sweep = new Sweep("test", "exp", Instant.now(), null, SweepStatus.PARTIAL, List.of("control"),
				List.of(new SweepVariantResolution("control", "s1", Instant.now(), null)), List.of("s1"), Map.of());

		assertThat(sweep.isComplete()).isTrue();
	}

	@Test
	void isNotCompleteWhenVariantsMissing() {
		Sweep sweep = new Sweep("test", "exp", Instant.now(), null, SweepStatus.RUNNING,
				List.of("control", "variant-a"),
				List.of(SweepVariantResolution.unresolved("control"), SweepVariantResolution.unresolved("variant-a")),
				List.of(), Map.of());

		assertThat(sweep.isComplete()).isFalse();
	}

	@Test
	void hasVersionMismatchWithDifferentCommits() {
		Sweep sweep = new Sweep("test", "exp", Instant.now(), null, SweepStatus.PARTIAL,
				List.of("control", "variant-a"),
				List.of(new SweepVariantResolution("control", "s1", Instant.now(), "abc123"),
						new SweepVariantResolution("variant-a", "s2", Instant.now(), "def456")),
				List.of("s1", "s2"), Map.of());

		assertThat(sweep.hasVersionMismatch()).isTrue();
	}

	@Test
	void noVersionMismatchWithSameCommit() {
		Sweep sweep = new Sweep("test", "exp", Instant.now(), null, SweepStatus.PARTIAL,
				List.of("control", "variant-a"),
				List.of(new SweepVariantResolution("control", "s1", Instant.now(), "abc123"),
						new SweepVariantResolution("variant-a", "s2", Instant.now(), "abc123")),
				List.of("s1", "s2"), Map.of());

		assertThat(sweep.hasVersionMismatch()).isFalse();
	}

	@Test
	void noVersionMismatchWithNullCommits() {
		Sweep sweep = new Sweep("test", "exp", Instant.now(), null, SweepStatus.PARTIAL,
				List.of("control", "variant-a"),
				List.of(new SweepVariantResolution("control", "s1", Instant.now(), null),
						new SweepVariantResolution("variant-a", "s2", Instant.now(), null)),
				List.of("s1", "s2"), Map.of());

		assertThat(sweep.hasVersionMismatch()).isFalse();
	}

	@Test
	void noVersionMismatchWithOneNullCommit() {
		Sweep sweep = new Sweep("test", "exp", Instant.now(), null, SweepStatus.PARTIAL,
				List.of("control", "variant-a"),
				List.of(new SweepVariantResolution("control", "s1", Instant.now(), "abc123"),
						new SweepVariantResolution("variant-a", "s2", Instant.now(), null)),
				List.of("s1", "s2"), Map.of());

		assertThat(sweep.hasVersionMismatch()).isFalse();
	}

	@Test
	void sweepStatusEnumSerializesAsString() throws Exception {
		String json = mapper.writeValueAsString(SweepStatus.PARTIAL);
		assertThat(json).isEqualTo("\"PARTIAL\"");

		SweepStatus restored = mapper.readValue(json, SweepStatus.class);
		assertThat(restored).isEqualTo(SweepStatus.PARTIAL);
	}

	@Test
	void expectedVariantsAreDefensivelyCopied() {
		var mutableList = new java.util.ArrayList<>(List.of("control", "variant-a"));
		Sweep sweep = new Sweep("test", "exp", Instant.now(), null, SweepStatus.RUNNING, mutableList, List.of(),
				List.of(), Map.of());

		mutableList.add("variant-b");
		assertThat(sweep.expectedVariants()).hasSize(2);
	}

	@Test
	void resolutionsAreDefensivelyCopied() {
		var mutableList = new java.util.ArrayList<>(List.of(SweepVariantResolution.unresolved("control")));
		Sweep sweep = new Sweep("test", "exp", Instant.now(), null, SweepStatus.RUNNING, List.of("control"),
				mutableList, List.of(), Map.of());

		mutableList.add(SweepVariantResolution.unresolved("extra"));
		assertThat(sweep.resolutions()).hasSize(1);
	}

	@Test
	void sessionHistoryIsDefensivelyCopied() {
		var mutableList = new java.util.ArrayList<>(List.of("session-1"));
		Sweep sweep = new Sweep("test", "exp", Instant.now(), null, SweepStatus.RUNNING, List.of("control"), List.of(),
				mutableList, Map.of());

		mutableList.add("session-2");
		assertThat(sweep.sessionHistory()).hasSize(1);
	}

	@Test
	void metadataIsDefensivelyCopied() {
		var mutableMap = new java.util.HashMap<>(Map.of("key", "value"));
		Sweep sweep = new Sweep("test", "exp", Instant.now(), null, SweepStatus.RUNNING, List.of(), List.of(),
				List.of(), mutableMap);

		mutableMap.put("extra", "val");
		assertThat(sweep.metadata()).hasSize(1);
	}

}
