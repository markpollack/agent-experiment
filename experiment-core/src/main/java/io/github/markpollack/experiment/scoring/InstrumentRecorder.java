package io.github.markpollack.experiment.scoring;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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
 * description, and the identity of the libraries involved.
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

	/**
	 * Library name to a class loaded from it and its Maven {@code groupId/artifactId}.
	 */
	private static final Map<String, Library> LIBRARIES = Map.of("agent-judge-core",
			new Library(Jury.class, "io.github.markpollack/agent-judge-core"), "experiment-core",
			new Library(InstrumentRecord.class, "io.github.markpollack/experiment-core"));

	private record Library(Class<?> type, String mavenPath) {
	}

	private InstrumentRecorder() {
	}

	/**
	 * Describe a jury as the instrument that will score a run.
	 * @param jury the configured jury, before it has voted
	 * @return the instrument record; never null, and carrying a failure when the jury
	 * could not be described
	 */
	public static InstrumentRecord describe(Jury jury) {
		Map<String, String> libraries = new TreeMap<>();
		Map<String, String> artifacts = new TreeMap<>();
		resolveLibraries(libraries, artifacts);
		try {
			Map<String, Object> description = jury.describe().toPortable();
			return new InstrumentRecord(descriptionVersion(description), hash(description), description, null,
					libraries, artifacts);
		}
		catch (RuntimeException ex) {
			logger.warn("Jury {} could not be described; recording the failure instead of its roster: {}",
					jury.getClass().getName(), ex.getMessage());
			return new InstrumentRecord(null, null, null, ex.getClass().getName() + ": " + ex.getMessage(), libraries,
					artifacts);
		}
	}

	static String hash(Map<String, Object> description) {
		try {
			return sha256(CANONICAL.writeValueAsString(description).getBytes(StandardCharsets.UTF_8));
		}
		catch (JsonProcessingException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static String sha256(byte[] content) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required of every Java platform", ex);
		}
	}

	private static Integer descriptionVersion(Map<String, Object> description) {
		return description.get("descriptionVersion") instanceof Number version ? version.intValue() : null;
	}

	/**
	 * Resolve each library's declared version and the identity of the jar it came from.
	 *
	 * <p>
	 * The version alone does not identify the code. A snapshot version resolves to a
	 * different build whenever the library's main branch moves, and the jar's own Maven
	 * metadata records only that floating string, so two runs months apart can report the
	 * same version and have been scored by different code. The jar's SHA-256 does not
	 * have that problem.
	 *
	 * <p>
	 * A library loaded from a class directory — a reactor build, an IDE — has neither,
	 * and is left out rather than recorded as a guess.
	 */
	static void resolveLibraries(Map<String, String> versions, Map<String, String> artifacts) {
		LIBRARIES.forEach((name, library) -> {
			String version = declaredVersion(library.mavenPath());
			if (version != null) {
				versions.put(name, version);
			}
			String jarHash = jarHash(library.type());
			if (jarHash != null) {
				artifacts.put(name, "sha256:" + jarHash);
			}
		});
	}

	private static String declaredVersion(String mavenPath) {
		try (InputStream in = InstrumentRecorder.class
			.getResourceAsStream("/META-INF/maven/" + mavenPath + "/pom.properties")) {
			if (in == null) {
				return null;
			}
			Properties properties = new Properties();
			properties.load(in);
			String version = properties.getProperty("version");
			return version == null || version.isBlank() ? null : version;
		}
		catch (IOException ex) {
			logger.debug("Could not read the declared version from {}: {}", mavenPath, ex.getMessage());
			return null;
		}
	}

	private static String jarHash(Class<?> type) {
		try {
			CodeSource source = type.getProtectionDomain().getCodeSource();
			if (source == null || source.getLocation() == null) {
				return null;
			}
			Path location = Path.of(source.getLocation().toURI());
			if (!Files.isRegularFile(location)) {
				return null; // a class directory has no artifact identity
			}
			return sha256(Files.readAllBytes(location));
		}
		catch (Exception ex) {
			logger.debug("Could not hash the artifact for {}: {}", type.getName(), ex.getMessage());
			return null;
		}
	}

	/** Library versions alone, for callers that do not need artifact identity. */
	static Map<String, String> libraries() {
		Map<String, String> versions = new LinkedHashMap<>();
		resolveLibraries(versions, new LinkedHashMap<>());
		return versions;
	}

}
