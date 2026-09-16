package io.github.markpollack.experiment.result;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.judge.result.Judgment;
import io.github.markpollack.judge.result.JudgmentReasonCode;
import io.github.markpollack.judge.jury.Verdict;
import io.github.markpollack.judge.jury.interpretation.Interpretation;
import io.github.markpollack.judge.jury.interpretation.Verdicts;
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
 * a reader gets wrong by accident, and each is a case where an absence could be rendered
 * as a measurement:
 *
 * <ul>
 * <li><b>nothing scored</b> — every item excluded. There is no rate.</li>
 * <li><b>no items at all</b> — same, with nothing to divide.</li>
 * <li><b>never judged</b> — the agent ran and no jury reached it.</li>
 * <li><b>judged and all failed</b> — a real 0.0, which must stay distinguishable from the
 * three above.</li>
 * </ul>
 *
 * <p>
 * <b>Every assertion here is made against the JSON as written, item by item.</b> An
 * earlier version asserted that the text contained one {@code "passed" : null} somewhere,
 * which a regression could satisfy by leaving one item right and breaking another.
 * Regeneration ({@code -Dconformance.regenerate=true}) rewrites the committed file, so
 * the assertions must be able to catch a regression on their own rather than relying on
 * the snapshot.
 */
class ConformanceFixturesTest {

	private static final Path FIXTURES = Path.of("src/test/resources/conformance");

	private static final ObjectMapper JSON = new ObjectMapper();

	@Test
	void nothingScoredHasNoRateAndEveryItemRecordsNoDecision(@TempDir Path dir) throws Exception {
		Fixture fixture = write(dir, "nothing-scored",
				List.of(item("a", RecordedJudgmentStatus.NOT_APPLICABLE), item("b", RecordedJudgmentStatus.ERROR)));

		assertThat(fixture.counts()).isEqualTo(new ItemCounts(0, 0, 2, 1, 0, 0));
		assertThat(fixture.counts().passRate()).isEmpty();
		// Both items, named individually: an explicit null, not an omission and not
		// false.
		assertThat(fixture.passedByItem()).containsExactly("null", "null");
	}

	@Test
	void aRunWithNoItemsHasNoRate(@TempDir Path dir) throws Exception {
		Fixture fixture = write(dir, "no-items", List.of());

		assertThat(fixture.counts()).isEqualTo(new ItemCounts(0, 0, 0, 0, 0, 0));
		assertThat(fixture.counts().passRate()).isEmpty();
		assertThat(fixture.passedByItem()).isEmpty();
	}

	@Test
	void neverJudgedIsNotTheSameAsFailing(@TempDir Path dir) throws Exception {
		Fixture fixture = write(dir, "never-judged",
				List.of(ItemResult.builder().itemId("a").itemSlug("a").success(true).build()));

		assertThat(fixture.counts()).isEqualTo(new ItemCounts(0, 0, 0, 0, 1, 0));
		assertThat(fixture.counts().passRate()).isEmpty();
		assertThat(fixture.passedByItem()).containsExactly("null");
	}

	@Test
	void judgedAndAllFailedIsARealZero(@TempDir Path dir) throws Exception {
		Fixture fixture = write(dir, "all-failed",
				List.of(item("a", RecordedJudgmentStatus.FAIL), item("b", RecordedJudgmentStatus.FAIL)));

		// The one case that genuinely is 0.0, and the reason the others must not be.
		assertThat(fixture.counts()).isEqualTo(new ItemCounts(0, 2, 0, 0, 0, 0));
		assertThat(fixture.counts().passRate()).hasValue(0.0);
		assertThat(fixture.passedByItem()).containsExactly("false", "false");
	}

	private record Fixture(ItemCounts counts, List<String> passedByItem) {
	}

	/**
	 * Write the fixture, check it against the committed one, and read back what each item
	 * actually recorded.
	 */
	private static Fixture write(Path dir, String name, List<ItemResult> items) throws Exception {
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
		return new Fixture(result.counts(), passedByItem(written));
	}

	/**
	 * What each item recorded for {@code passed}, as written: absent, null, true or
	 * false.
	 */
	private static List<String> passedByItem(String written) throws Exception {
		List<String> flags = new ArrayList<>();
		for (JsonNode item : JSON.readTree(written).path("items")) {
			JsonNode passed = item.get("passed");
			flags.add(passed == null ? "absent" : passed.isNull() ? "null" : passed.asText());
		}
		return flags;
	}

	private static ItemResult item(String id, RecordedJudgmentStatus status) {
		// Built from a live verdict, because that is what a run has when it records one.
		// The fixture then shows what a real run writes, interpretation included, rather
		// than a shape assembled only for the test.
		Judgment judgment = switch (status) {
			case PASS -> Judgment.pass("recorded");
			case FAIL -> Judgment.fail("recorded");
			case ABSTAIN -> Judgment.abstain("recorded");
			case NOT_APPLICABLE -> Judgment.notApplicable("recorded");
			case ERROR -> Judgment.error(JudgmentReasonCode.JUDGE_FAILED, "recorded");
		};
		Verdict verdict = Verdict.single("recorded", judgment);
		Interpretation interpretation = Verdicts.interpret(verdict);
		// Derived the same way the runner derives it, so the fixture shows what a real
		// run writes: absent when the jury decided nothing, false only when it decided
		// against.
		return ItemResult.builder()
			.itemId(id)
			.itemSlug(id)
			.success(true)
			.passed(ItemAccounting.passedFlag(interpretation))
			.verdict(RecordedVerdict.from(verdict))
			.interpretation(interpretation)
			.build();
	}

}
