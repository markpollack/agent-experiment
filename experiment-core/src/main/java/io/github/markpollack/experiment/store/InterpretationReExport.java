package io.github.markpollack.experiment.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.jspecify.annotations.Nullable;

/**
 * Adds an {@code interpretation} beside each stored verdict in an existing result file,
 * without changing the verdict.
 *
 * <p>
 * Results written from now on carry their interpretation from the recorder, where the
 * live verdict still exists. This exists for the results already on disk, which were
 * written before the interpretation did.
 *
 * <p>
 * Three properties, in the order they matter:
 *
 * <ol>
 * <li><b>The stored verdict is not touched.</b> Every file is parsed as a tree rather
 * than into the typed records, because the typed records drop what they do not model, and
 * before anything is written each verdict is compared to the one that was read.
 * <li><b>Formatting may change; content may not.</b> Stored files were written by more
 * than one serializer configuration — some escape non-ASCII, most do not — so no single
 * writer reproduces every file byte for byte. The check is therefore that the verdict
 * parses equal, which is the strongest statement the archive can support.
 * <li><b>Running it twice does nothing the first run did not.</b> An item already
 * carrying the current schema version is left alone; an older one is replaced.
 * </ol>
 */
public final class InterpretationReExport {

	/**
	 * Produces the interpretation of one stored verdict.
	 *
	 * <p>
	 * A seam rather than a direct call, so that the file walking, the preservation check
	 * and the report can be built and tested against a stub while the library API this
	 * delegates to is still being written.
	 */
	@FunctionalInterface
	public interface VerdictInterpreter {

		/**
		 * @param storedVerdict a verdict exactly as it appears in the file, of any age
		 * @return its interpretation, or {@code null} to leave this verdict alone
		 */
		@Nullable ObjectNode interpret(JsonNode storedVerdict);

	}

	/** What one file's re-export did, or would do. */
	public record FileOutcome(Path file, int items, int added, int replaced, int upToDate, boolean written,
			@Nullable String failure) {

		public boolean failed() {
			return failure != null;
		}

	}

	/** What the whole run did, or would do. */
	public record Report(List<FileOutcome> files, Map<String, Integer> defectsByKind) {

		public int filesFailed() {
			return (int) files.stream().filter(FileOutcome::failed).count();
		}

		public int added() {
			return files.stream().mapToInt(FileOutcome::added).sum();
		}

		public int replaced() {
			return files.stream().mapToInt(FileOutcome::replaced).sum();
		}

		public int upToDate() {
			return files.stream().mapToInt(FileOutcome::upToDate).sum();
		}

		/** The dry-run report, and the evidence that the re-export did what it said. */
		public String summary() {
			StringBuilder out = new StringBuilder();
			out.append("files            : ").append(files.size()).append('\n');
			out.append("  failed         : ").append(filesFailed()).append('\n');
			out.append("interpretations  : ")
				.append(added())
				.append(" added, ")
				.append(replaced())
				.append(" replaced, ")
				.append(upToDate())
				.append(" already current\n");
			out.append("defects by kind  :");
			if (defectsByKind.isEmpty()) {
				out.append(" none");
			}
			defectsByKind.forEach((kind, count) -> out.append("\n    ").append(count).append("  ").append(kind));
			for (FileOutcome outcome : files) {
				if (outcome.failed()) {
					out.append("\n  FAILED ").append(outcome.file()).append(": ").append(outcome.failure());
				}
			}
			return out.toString();
		}

	}

	private static final String INTERPRETATION = "interpretation";

	private static final String SCHEMA_VERSION = "schemaVersion";

	private final VerdictInterpreter interpreter;

	private final int schemaVersion;

	private final ObjectMapper mapper;

	public InterpretationReExport(VerdictInterpreter interpreter, int schemaVersion) {
		this.interpreter = interpreter;
		this.schemaVersion = schemaVersion;
		this.mapper = new ObjectMapper();
		this.mapper.registerModule(new JavaTimeModule());
		this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
	}

	/**
	 * Re-export every file, or report what doing so would change.
	 * @param files result files to re-export
	 * @param dryRun when true, nothing is written; everything else, including the
	 * preservation check, is done exactly as it would be
	 */
	public Report run(List<Path> files, boolean dryRun) {
		List<FileOutcome> outcomes = new ArrayList<>();
		Map<String, Integer> defects = new TreeMap<>();
		for (Path file : files) {
			outcomes.add(reExport(file, dryRun, defects));
		}
		return new Report(List.copyOf(outcomes), Map.copyOf(defects));
	}

	private FileOutcome reExport(Path file, boolean dryRun, Map<String, Integer> defects) {
		JsonNode root;
		try {
			root = this.mapper.readTree(Files.readString(file));
		}
		catch (IOException | RuntimeException ex) {
			return new FileOutcome(file, 0, 0, 0, 0, false, "unreadable: " + ex.getMessage());
		}

		// Keep what was read, so the verdicts can be compared rather than trusted.
		Map<Integer, JsonNode> verdictsAsRead = new LinkedHashMap<>();
		int index = 0;
		for (JsonNode item : root.path("items")) {
			if (item.hasNonNull("verdict")) {
				verdictsAsRead.put(index, item.get("verdict").deepCopy());
			}
			index++;
		}

		int items = 0, added = 0, replaced = 0, upToDate = 0;
		index = 0;
		for (JsonNode item : root.path("items")) {
			int at = index++;
			items++;
			if (!(item instanceof ObjectNode itemNode) || !item.hasNonNull("verdict")) {
				continue;
			}
			JsonNode existing = itemNode.get(INTERPRETATION);
			if (existing != null && existing.path(SCHEMA_VERSION).asInt(-1) == this.schemaVersion) {
				upToDate++;
				countDefects(existing, defects);
				continue;
			}
			ObjectNode interpretation = this.interpreter.interpret(itemNode.get("verdict"));
			if (interpretation == null) {
				continue;
			}
			// One source of truth for the version. If the library has moved and this tool
			// has not, every run would replace what the last run wrote and call it work;
			// say so instead.
			int written = interpretation.path(SCHEMA_VERSION).asInt(-1);
			if (written != this.schemaVersion) {
				return new FileOutcome(file, items, 0, 0, 0, false, "interpreter produced schemaVersion " + written
						+ " where " + this.schemaVersion + " was expected; nothing written");
			}
			// set() rather than put-then-populate: the key is replaced wholesale, so a
			// stale interpretation cannot leave a field behind in the new one.
			itemNode.set(INTERPRETATION, interpretation);
			countDefects(interpretation, defects);
			if (existing != null) {
				replaced++;
			}
			else {
				added++;
			}
		}

		for (Map.Entry<Integer, JsonNode> entry : verdictsAsRead.entrySet()) {
			JsonNode now = root.path("items").get(entry.getKey()).get("verdict");
			if (!entry.getValue().equals(now)) {
				return new FileOutcome(file, items, 0, 0, 0, false,
						"verdict changed at item " + entry.getKey() + "; nothing written");
			}
		}

		if (added == 0 && replaced == 0) {
			return new FileOutcome(file, items, 0, 0, upToDate, false, null);
		}
		if (!dryRun) {
			try {
				Files.writeString(file, this.mapper.writeValueAsString(root));
			}
			catch (IOException ex) {
				return new FileOutcome(file, items, added, replaced, upToDate, false,
						"write failed: " + ex.getMessage());
			}
		}
		return new FileOutcome(file, items, added, replaced, upToDate, !dryRun, null);
	}

	private static void countDefects(JsonNode interpretation, Map<String, Integer> defects) {
		for (JsonNode defect : interpretation.path("defects")) {
			defects.merge(defect.path("kind").asText("UNKNOWN"), 1, Integer::sum);
		}
	}

}
