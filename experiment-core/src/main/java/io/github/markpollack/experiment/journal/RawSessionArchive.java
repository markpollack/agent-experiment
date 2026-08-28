/*
 * Copyright 2024-2026 Mark Pollack
 *
 * Licensed under the Business Source License 1.1 (the "License").
 */

package io.github.markpollack.experiment.journal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Copies the agent CLI's own session transcript into the run directory, verbatim.
 *
 * <h2>Why this exists</h2>
 *
 * Every gap the 2026-08-24 measurement audit found — per-step tokens,
 * {@code stop_reason}, per-tool {@code duration_ms} — was plausibly already in the
 * provider's raw log and was discarded at parse time. Per-tool {@code duration_ms} was
 * captured in v1 and silently gone by v3, and the analysis that needed it could not be
 * done at all. Archiving converts "we cannot answer that" into "re-derive it", at storage
 * cost instead of a re-run.
 *
 * <p>
 * <strong>This is the only irreversible part of experiment capture.</strong> Analysis can
 * be redone against kept data; a session file that was never copied is gone once the CLI
 * prunes it.
 *
 * <h2>Rules, and why each one matters</h2>
 *
 * <ul>
 * <li><strong>Verbatim.</strong> No normalising, filtering or truncating. An archive that
 * keeps only what today's analysis consumes reproduces the {@code duration_ms} failure
 * one generation later. A sampled Claude session carries seven event types and only two
 * survive any projection this codebase currently performs.</li>
 * <li><strong>Content-addressed</strong> by SHA-256 of the original bytes, so
 * verbatimness is checkable rather than asserted, and so a resumed session contributing
 * more than one file does not collide.</li>
 * <li><strong>No size cap.</strong> A cap is a filter, and the largest sessions are the
 * long autonomous runs most worth re-analysing.</li>
 * <li><strong>Nothing rather than a guess.</strong> If the session id does not resolve to
 * exactly one file, the manifest records why and no bytes are copied. A wrong file
 * archived confidently is worse than an absent one, because it fails silently.</li>
 * </ul>
 *
 * Claude Code first. Codex (sqlite) and Gemini (OTEL) follow the same shape; a vendor
 * abstraction must not block the first implementation.
 */
final class RawSessionArchive {

	static final String RAW_DIR = "raw";

	static final String MANIFEST = "MANIFEST.json";

	private static final Logger logger = LoggerFactory.getLogger(RawSessionArchive.class);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private RawSessionArchive() {
	}

	/**
	 * The default search root for Claude Code session files. The transcript filename is
	 * the session uuid, which is what makes resolution a lookup rather than a heuristic.
	 * @return {@code ~/.claude/projects}
	 */
	static Path defaultClaudeProjectsRoot() {
		return Path.of(System.getProperty("user.home"), ".claude", "projects");
	}

	/**
	 * Archives the provider transcript for one run.
	 * <p>
	 * Never throws on a copy failure: a run that produced results must not be lost
	 * because its transcript could not be archived. Failures are recorded in the manifest
	 * and logged, so an absent archive is always explained rather than merely missing.
	 * @param runArtifactDir the run's own directory — the one holding {@code run.json}
	 * @param providerSessionId the provider session id, or null/{@code none} if the
	 * provider returned none
	 * @param searchRoot where provider transcripts live
	 * @return the manifest path, or null if nothing could be written at all
	 */
	static @Nullable Path archive(Path runArtifactDir, @Nullable String providerSessionId, Path searchRoot) {
		Map<String, Object> manifest = new LinkedHashMap<>();
		manifest.put("schema", 1);
		manifest.put("captured_at", Instant.now().toString());
		manifest.put("provider", "claude-code");
		manifest.put("provider_session_id",
				providerSessionId != null ? providerSessionId : ExperimentJournal.NO_PROVIDER_SESSION);

		List<Map<String, Object>> files = new ArrayList<>();
		try {
			Path rawDir = runArtifactDir.resolve(RAW_DIR);
			Files.createDirectories(rawDir);

			if (providerSessionId == null || ExperimentJournal.NO_PROVIDER_SESSION.equals(providerSessionId)) {
				manifest.put("archived", false);
				manifest.put("reason", "the provider returned no session id, so there is nothing to resolve");
			}
			else {
				List<Path> found = resolve(searchRoot, providerSessionId);
				if (found.size() != 1) {
					manifest.put("archived", false);
					manifest.put("reason", found.isEmpty()
							? "no transcript found for this session id under " + searchRoot
							: "ambiguous: " + found.size() + " transcripts match this session id under " + searchRoot);
					logger.warn("No raw transcript archived for session {}: {}", providerSessionId,
							manifest.get("reason"));
				}
				else {
					files.add(copy(found.get(0), rawDir, providerSessionId));
					manifest.put("archived", true);
				}
			}
			manifest.put("files", files);
			Path manifestPath = rawDir.resolve(MANIFEST);
			MAPPER.writerWithDefaultPrettyPrinter().writeValue(manifestPath.toFile(), manifest);
			return manifestPath;
		}
		catch (IOException | RuntimeException ex) {
			logger.error("Failed to archive raw session for {}: {}", providerSessionId, ex.getMessage(), ex);
			return null;
		}
	}

	private static List<Path> resolve(Path searchRoot, String sessionId) throws IOException {
		if (!Files.isDirectory(searchRoot)) {
			return List.of();
		}
		String wanted = sessionId + ".jsonl";
		try (Stream<Path> walk = Files.walk(searchRoot)) {
			return walk.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().equals(wanted)).toList();
		}
	}

	private static Map<String, Object> copy(Path source, Path rawDir, String sessionId) throws IOException {
		byte[] bytes = Files.readAllBytes(source);
		String digest = sha256(bytes);
		Path target = rawDir.resolve(digest + ".jsonl");
		// Copy from the original file rather than writing `bytes` back out, so the
		// archived
		// artifact is a copy of what was read and not a re-serialisation of it.
		Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);

		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("sha256", digest);
		entry.put("bytes", bytes.length);
		entry.put("stored_as", RAW_DIR + "/" + target.getFileName());
		entry.put("source_path", source.toAbsolutePath().toString());
		entry.put("provider_session_id", sessionId);
		entry.put("source_mtime", Files.getLastModifiedTime(source).toInstant().toString());
		return entry;
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (java.security.NoSuchAlgorithmException ex) {
			throw new UncheckedIOException(new IOException("SHA-256 unavailable", ex));
		}
	}

}
