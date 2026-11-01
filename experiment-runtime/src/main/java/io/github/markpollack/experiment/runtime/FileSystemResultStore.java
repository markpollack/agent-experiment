package io.github.markpollack.experiment.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.markpollack.experiment.runtime.AgentRecords.Result;

/**
 * Immutable saves. Every read validates the complete current envelope through record
 * constructors.
 */
public final class FileSystemResultStore implements ResultStore {

	private final Path root;

	public FileSystemResultStore(Path root) {
		this.root = root.toAbsolutePath().normalize();
	}

	private Path directory(String id) {
		AgentRecords.text(id);
		return root
			.resolve(Base64.getUrlEncoder().withoutPadding().encodeToString(id.getBytes(StandardCharsets.UTF_8)));
	}

	@Override
	public void save(Result result) throws IOException {
		// Encode/validate before acquiring the immutable run directory.
		byte[] bytes = RuntimeJson.mapper().writeValueAsBytes(result);
		RuntimeJson.mapper().readValue(bytes, Result.class);
		Files.createDirectories(root);
		Path dir = directory(result.experimentId());
		Files.createDirectory(dir); // exclusive; existing failed/incomplete saves cannot
									// be silently replaced
		Path pending = dir.resolve("pending.json");
		Files.write(pending, bytes, StandardOpenOption.CREATE_NEW);
		Files.move(pending, dir.resolve("run.json"), StandardCopyOption.ATOMIC_MOVE);
	}

	@Override
	public Optional<Result> load(String id) throws IOException {
		Path dir = directory(id);
		if (!Files.exists(dir))
			return Optional.empty();
		Result result = read(dir.resolve("run.json"));
		if (!result.experimentId().equals(id))
			throw new IOException("Store/run identity mismatch");
		return Optional.of(result);
	}

	private Result read(Path path) throws IOException {
		JsonNode tree = RuntimeJson.mapper().readTree(path.toFile());
		if (!tree.path("recordingSchemaVersion").isInt() || tree.path("recordingSchemaVersion").intValue() != 3
				|| !tree.path("kind").asText().equals("AGENT_CANONICAL"))
			throw new IOException("Unsupported current result; use explicit historical reader for old records");
		return RuntimeJson.mapper().treeToValue(tree, Result.class);
	}

	@Override
	public List<Result> list() throws IOException {
		if (!Files.exists(root))
			return List.of();
		List<Result> results = new ArrayList<>();
		try (var dirs = Files.list(root)) {
			for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
				Result result = read(dir.resolve("run.json"));
				if (!directory(result.experimentId()).equals(dir))
					throw new IOException("Store directory identity mismatch");
				results.add(result);
			}
		}
		return List.copyOf(results);
	}

	@Override
	public Optional<Result> mostRecent() throws IOException {
		return list().stream().max(Comparator.comparingLong(Result::createdAtEpochMs));
	}

	/**
	 * Historical raw records remain unchanged; no historical verdict is reinterpreted as
	 * V6.
	 */
	public static HistoricalResult readHistorical(Path file) throws IOException {
		byte[] bytes = Files.readAllBytes(file);
		JsonNode tree = RuntimeJson.mapper().readTree(bytes);
		if (!tree.isObject() || !tree.path("experimentId").isTextual() || !tree.path("items").isArray())
			throw new IOException("Not a historical Experiment envelope");
		if (tree.has("recordingSchemaVersion") && (!tree.path("recordingSchemaVersion").isInt()
				|| tree.path("recordingSchemaVersion").intValue() != 2))
			throw new IOException("Unsupported historical version");
		if (tree.has("kind"))
			throw new IOException("Not an admitted historical kind");
		return new HistoricalResult(new String(bytes, StandardCharsets.UTF_8), Evidence.sha256(bytes),
				"LEGACY_UNVERIFIED");
	}

	public record HistoricalResult(String originalJson, String sha256, String state) {
	}

}
