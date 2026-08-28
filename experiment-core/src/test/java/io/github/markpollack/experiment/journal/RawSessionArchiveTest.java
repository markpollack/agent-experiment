package io.github.markpollack.experiment.journal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.journal.claude.PhaseCapture;
import io.github.markpollack.journal.claude.ToolUseRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The archive must hold the provider transcript <em>as emitted</em>.
 *
 * <p>
 * Every assertion here is against bytes on disk. That is deliberate: the defect this
 * whole slice exists to prevent — per-tool {@code duration_ms} captured in v1 and gone by
 * v3 — was invisible to tests that checked objects rather than files.
 */
class RawSessionArchiveTest {

	private static final String SESSION = "3f2a9c10-7b44-4e2b-9d61-0c5e8a1b2d33";

	@Test
	void archivesTheTranscriptByteForByteUnderItsOwnDigest(@TempDir Path root) throws Exception {
		Path fixtures = transcriptFixture(root, SESSION);
		byte[] original = Files.readAllBytes(fixtures.resolve("proj-a").resolve(SESSION + ".jsonl"));

		Path runDir = Files.createDirectories(root.resolve("runs/run-1"));
		RawSessionArchive.archive(runDir, SESSION, fixtures);

		String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original));
		Path archived = runDir.resolve("raw").resolve(digest + ".jsonl");
		assertThat(archived).exists();
		assertThat(Files.readAllBytes(archived))
			.as("the archived file must be byte-identical to what the provider emitted")
			.isEqualTo(original);
	}

	/**
	 * The reason to keep the file rather than a parse of it, asserted rather than argued:
	 * event types the experiment's own schema has no place for must survive the round
	 * trip.
	 */
	@Test
	void keepsEventTypesNoCurrentProjectionWouldRetain(@TempDir Path root) throws Exception {
		Path fixtures = transcriptFixture(root, SESSION);
		Path runDir = Files.createDirectories(root.resolve("runs/run-1"));
		RawSessionArchive.archive(runDir, SESSION, fixtures);

		String archived = Files.readString(onlyArchivedTranscript(runDir));
		assertThat(archived).contains("\"type\":\"attachment\"")
			.contains("\"type\":\"last-prompt\"")
			.contains("\"type\":\"ai-title\"");
	}

	@Test
	void manifestRecordsDigestSourceAndSession(@TempDir Path root) throws Exception {
		Path fixtures = transcriptFixture(root, SESSION);
		Path runDir = Files.createDirectories(root.resolve("runs/run-1"));
		RawSessionArchive.archive(runDir, SESSION, fixtures);

		JsonNode manifest = new ObjectMapper().readTree(runDir.resolve("raw/MANIFEST.json").toFile());
		assertThat(manifest.get("archived").asBoolean()).isTrue();
		assertThat(manifest.get("provider_session_id").asText()).isEqualTo(SESSION);
		JsonNode file = manifest.get("files").get(0);
		assertThat(file.get("sha256").asText()).hasSize(64);
		assertThat(file.get("source_path").asText()).endsWith(SESSION + ".jsonl");
		assertThat(file.get("bytes").asInt()).isPositive();
	}

	/**
	 * Nothing rather than a guess. A wrong transcript archived confidently is worse than
	 * an absent one, because the failure is silent — which is the exact shape of the
	 * defect this slice exists to prevent.
	 */
	@Test
	void writesNoBytesButRecordsWhyWhenTheSessionDoesNotResolve(@TempDir Path root) throws Exception {
		Path fixtures = transcriptFixture(root, "some-other-session");
		Path runDir = Files.createDirectories(root.resolve("runs/run-1"));
		RawSessionArchive.archive(runDir, SESSION, fixtures);

		JsonNode manifest = new ObjectMapper().readTree(runDir.resolve("raw/MANIFEST.json").toFile());
		assertThat(manifest.get("archived").asBoolean()).isFalse();
		assertThat(manifest.get("reason").asText()).contains("no transcript found");
		assertThat(manifest.get("files")).isEmpty();
		try (Stream<Path> walk = Files.walk(runDir.resolve("raw"))) {
			assertThat(walk.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(".jsonl")))
				.as("no transcript may be written when the id does not resolve")
				.isEmpty();
		}
	}

	@Test
	void recordsAnAbsentProviderSessionRatherThanFailing(@TempDir Path root) throws Exception {
		Path runDir = Files.createDirectories(root.resolve("runs/run-1"));
		RawSessionArchive.archive(runDir, null, root.resolve("nowhere"));

		JsonNode manifest = new ObjectMapper().readTree(runDir.resolve("raw/MANIFEST.json").toFile());
		assertThat(manifest.get("archived").asBoolean()).isFalse();
		assertThat(manifest.get("provider_session_id").asText()).isEqualTo(ExperimentJournal.NO_PROVIDER_SESSION);
		assertThat(manifest.get("reason").asText()).contains("no session id");
	}

	/**
	 * End to end through the journal: a real run leaves a real archive beside its
	 * run.json.
	 */
	@Test
	void aJournalledRunArchivesItsTranscriptBesideRunJson(@TempDir Path root) throws Exception {
		Path fixtures = transcriptFixture(root, SESSION);
		Path journalRoot = ExperimentJournal.journalRoot(root.resolve("artifacts"));
		ExperimentJournal journal = ExperimentJournal.open(journalRoot, "raw-exp").withRawSearchRoot(fixtures);

		try (RunJournal run = journal.openItem("ITEM-1", "item-1", "sonnet", "arm-a", "sweep-1", SESSION)) {
			run.recordPhase(phase());
			run.finish();
		}

		Path runJson = onlyFile(journalRoot, "run.json");
		Path manifest = runJson.getParent().resolve("raw/MANIFEST.json");
		assertThat(manifest).as("raw/ must land beside run.json, per (run_id, item_id)").exists();
		assertThat(new ObjectMapper().readTree(manifest.toFile()).get("archived").asBoolean()).isTrue();
	}

	private static Path transcriptFixture(Path root, String sessionId) throws Exception {
		Path dir = Files.createDirectories(root.resolve("claude-projects").resolve("proj-a"));
		Files.writeString(dir.resolve(sessionId + ".jsonl"), """
				{"type":"user","sessionId":"%s","content":"do the thing"}
				{"type":"attachment","sessionId":"%s","path":"/tmp/x"}
				{"type":"last-prompt","sessionId":"%s","text":"..."}
				{"type":"ai-title","sessionId":"%s","title":"Do the thing"}
				{"type":"assistant","sessionId":"%s","content":"done"}
				""".formatted(sessionId, sessionId, sessionId, sessionId, sessionId));
		return root.resolve("claude-projects");
	}

	private static Path onlyArchivedTranscript(Path runDir) throws Exception {
		try (Stream<Path> walk = Files.walk(runDir.resolve("raw"))) {
			List<Path> f = walk.filter(Files::isRegularFile)
				.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
				.toList();
			assertThat(f).hasSize(1);
			return f.get(0);
		}
	}

	private static Path onlyFile(Path root, String name) throws Exception {
		try (Stream<Path> walk = Files.walk(root)) {
			List<Path> f = walk.filter(Files::isRegularFile)
				.filter(p -> p.getFileName().toString().equals(name))
				.toList();
			assertThat(f).hasSize(1);
			return f.get(0);
		}
	}

	private static PhaseCapture phase() {
		return new PhaseCapture("explore", "task", 1000, 200, 0, 0, 0, 1500L, 1200L, 0.02, SESSION, 1, false, "done",
				List.of(), List.of(new ToolUseRecord("t1", "Read", Map.of())), "raw", List.of(), List.of(), List.of());
	}

}
