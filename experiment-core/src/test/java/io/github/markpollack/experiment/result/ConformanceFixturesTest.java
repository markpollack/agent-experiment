package io.github.markpollack.experiment.result;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.markpollack.experiment.attestation.ItemAccounting;
import io.github.markpollack.experiment.store.FileSystemResultStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fixtures every reader of these results must agree with, written by the real writer.
 *
 * <p>
 * A reader in another language cannot share this code, so what stops it drifting is a
 * file this writer produced and a number this writer expects. The cases here are the ones
 * a reader gets wrong by accident, and each one is a case where an absence could be
 * rendered as a measurement:
 *
 * <ul>
 * <li><b>nothing scored</b> — every item excluded. There is no rate. A reader that emits
 * 0.0 here draws a run that measured nothing at the same height as a run that failed
 * everything.</li>
 * <li><b>no items at all</b> — same, with nothing to divide.</li>
 * <li><b>never judged</b> — the agent ran and no jury reached it. Distinct from
 * failing.</li>
 * <li><b>judged and all failed</b> — a real 0.0, which must stay distinguishable from the
 * three above.</li>
 * </ul>
 *
 * <p>
 * Regenerate with {@code -Dconformance.regenerate=true} after a deliberate format change.
 * The committed files are then the contract other languages test against.
 */
class ConformanceFixturesTest {

	private static final Path FIXTURES = Path.of("src/test/resources/conformance");

	@Test
	void nothingScoredHasNoRate(@TempDir Path dir) throws Exception {
		ItemCounts counts = fixture(dir, "nothing-scored",
				List.of(item("a", RecordedJudgmentStatus.NOT_APPLICABLE), item("b", RecordedJudgmentStatus.ERROR)));

		assertThat(counts).isEqualTo(new ItemCounts(0, 0, 2, 1, 0, 0));
		assertThat(counts.passRate()).isEmpty();
		assertThat(counts.scored()).isZero();
	}

	@Test
	void aRunWithNoItemsHasNoRate(@TempDir Path dir) throws Exception {
		ItemCounts counts = fixture(dir, "no-items", List.of());

		assertThat(counts).isEqualTo(new ItemCounts(0, 0, 0, 0, 0, 0));
		assertThat(counts.passRate()).isEmpty();
	}

	@Test
	void neverJudgedIsNotTheSameAsFailing(@TempDir Path dir) throws Exception {
		ItemCounts neverJudged = fixture(dir, "never-judged",
				List.of(ItemResult.builder().itemId("a").itemSlug("a").success(true).build()));

		assertThat(neverJudged).isEqualTo(new ItemCounts(0, 0, 0, 0, 1, 0));
		// No jury reached this item, so there is no rate to report — not a zero one.
		assertThat(neverJudged.passRate()).isEmpty();
		assertThat(neverJudged.notJudged()).isEqualTo(1);
	}

	@Test
	void judgedAndAllFailedIsARealZero(@TempDir Path dir) throws Exception {
		ItemCounts counts = fixture(dir, "all-failed",
				List.of(item("a", RecordedJudgmentStatus.FAIL), item("b", RecordedJudgmentStatus.FAIL)));

		// The one case that genuinely is 0.0, and the reason the others must not be.
		assertThat(counts).isEqualTo(new ItemCounts(0, 2, 0, 0, 0, 0));
		assertThat(counts.passRate()).hasValue(0.0);
	}

	/**
	 * Write the fixture and check it against the committed one, so a format change that
	 * nobody meant shows up here rather than in another language's reader.
	 */
	private static ItemCounts fixture(Path dir, String name, List<ItemResult> items) throws Exception {
		ExperimentResult result = ExperimentResult.builder()
			.experimentId(name)
			.experimentName("conformance")
			.datasetSemanticVersion("1.0.0")
			.timestamp(Instant.parse("2026-09-15T00:00:00Z"))
			.items(items)
			.counts(ItemAccounting.count(items))
			.build();
		new FileSystemResultStore(dir).save(result);
		String written = Files.readString(dir.resolve("conformance").resolve(name + ".json"));

		Path committed = FIXTURES.resolve(name + ".json");
		if (Boolean.getBoolean("conformance.regenerate")) {
			Files.createDirectories(FIXTURES);
			Files.writeString(committed, written);
		}
		assertThat(committed).as("committed fixture; regenerate with -Dconformance.regenerate=true").exists();
		assertThat(written).isEqualTo(Files.readString(committed));
		// No stored rate anywhere: a reader has to derive one, and deriving forces it to
		// handle a denominator of zero.
		assertThat(written).doesNotContain("passRate");
		return result.counts();
	}

	@Test
	void anUndecidedItemWritesPassedAsAnExplicitNullRatherThanOmittingIt(@TempDir Path dir) throws Exception {
		fixture(dir, "nothing-scored",
				List.of(item("a", RecordedJudgmentStatus.NOT_APPLICABLE), item("b", RecordedJudgmentStatus.ERROR)));

		String written = Files.readString(FIXTURES.resolve("nothing-scored.json"));

		// Explicit null, not omission. An omitted field lets a reader write
		// get("passed", False) and carry on; a null makes that line fail where it stands.
		assertThat(written).contains("\"passed\" : null");
	}

	private static ItemResult item(String id, RecordedJudgmentStatus status) {
		RecordedVerdict verdict = new RecordedVerdict(
				new RecordedJudgment(status, null, null, status == RecordedJudgmentStatus.ERROR ? "judge_failed" : null,
						"recorded", List.of(), Map.of()),
				List.of(), Map.of(), Map.of(), List.of(), new RecordedDecision("own", null, null), List.of(), null);
		// Derived the same way the runner derives it, so the fixture shows what a real
		// run
		// writes: absent when the jury decided nothing, false only when it decided
		// against.
		return ItemResult.builder()
			.itemId(id)
			.itemSlug(id)
			.success(true)
			.passed(ItemAccounting.passedFlag(verdict))
			.verdict(verdict)
			.build();
	}

}
