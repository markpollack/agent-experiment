package io.github.markpollack.experiment.scoring;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.markpollack.experiment.result.InstrumentRecord;
import io.github.markpollack.judge.jury.Jury;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records the instrument a jury is: its description before any vote, the hash of that
 * description, and the versions of the libraries involved.
 *
 * <p>
 * A jury that cannot be described is recorded as such and does not stop the run. Absence
 * is recorded rather than fatal: a bookkeeping failure that blocks a run gets switched
 * off under deadline, while a mark that travels with the data does not.
 */
public final class InstrumentRecorder {

	private static final Logger logger = LoggerFactory.getLogger(InstrumentRecorder.class);

	/** Sorted keys at every level, so the same description always hashes the same. */
	private static final ObjectMapper CANONICAL = new ObjectMapper()
		.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

	/** Library name to its Maven {@code groupId/artifactId} path. */
	private static final Map<String, String> LIBRARIES = Map.of("agent-judge-core",
			"io.github.markpollack/agent-judge-core", "experiment-core", "io.github.markpollack/experiment-core");

	private InstrumentRecorder() {
	}

	/**
	 * Describe a jury as the instrument that will score a run.
	 * @param jury the configured jury, before it has voted
	 * @return the instrument record; never null, and carrying a failure when the jury
	 * could not be described
	 */
	public static InstrumentRecord describe(Jury jury) {
		Map<String, String> libraries = libraries();
		try {
			Map<String, Object> description = jury.describe().toPortable();
			return new InstrumentRecord(descriptionVersion(description), hash(description), description, null,
					libraries);
		}
		catch (RuntimeException ex) {
			logger.warn("Jury {} could not be described; recording the failure instead of its roster: {}",
					jury.getClass().getName(), ex.getMessage());
			return new InstrumentRecord(null, null, null, ex.getClass().getName() + ": " + ex.getMessage(), libraries);
		}
	}

	static String hash(Map<String, Object> description) {
		try {
			byte[] canonical = CANONICAL.writeValueAsString(description).getBytes(StandardCharsets.UTF_8);
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
		}
		catch (JsonProcessingException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required of every Java platform", ex);
		}
	}

	private static Integer descriptionVersion(Map<String, Object> description) {
		return description.get("descriptionVersion") instanceof Number version ? version.intValue() : null;
	}

	/**
	 * Versions from each library jar's Maven metadata. A library loaded from a class
	 * directory — a reactor build, an IDE — has no such metadata and is left out rather
	 * than recorded as a guess.
	 */
	static Map<String, String> libraries() {
		Map<String, String> versions = new TreeMap<>();
		LIBRARIES.forEach((name, path) -> {
			try (InputStream in = InstrumentRecorder.class
				.getResourceAsStream("/META-INF/maven/" + path + "/pom.properties")) {
				if (in != null) {
					Properties properties = new Properties();
					properties.load(in);
					String version = properties.getProperty("version");
					if (version != null && !version.isBlank()) {
						versions.put(name, version);
					}
				}
			}
			catch (IOException ex) {
				logger.debug("Could not read the version of {}: {}", name, ex.getMessage());
			}
		});
		return versions;
	}

}
