package io.github.markpollack.experiment.recording;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;

/** Exact source declaration and identities of the loaded artifacts. */
public record RuntimeIdentity(String revision, Map<String, String> artifacts) {
	public RuntimeIdentity {
		if (revision == null || revision.isBlank())
			throw new IllegalArgumentException("Source revision is required");
		artifacts = Map.copyOf(artifacts);
		if (artifacts.isEmpty())
			throw new IllegalArgumentException("Artifact identity is required");
	}

	/**
	 * Identify the producer actually on the runtime classpath, with an explicit source
	 * declaration.
	 */
	public static RuntimeIdentity judge() {
		String revision = System.getProperty("experiment.judge.revision", loadedJudgeRevision());
		Map<String, String> artifacts = new LinkedHashMap<>();
		artifacts.put("agent-judge-core", artifact(io.github.markpollack.judge.verdict.Verdict.class));
		for (String[] module : new String[][] { { "agent-judge-exec", "io.github.markpollack.judge.exec.CommandJudge" },
				{ "agent-judge-jev", "io.github.markpollack.judge.jev.JevJudge" } }) {
			try {
				artifacts.put(module[0],
						artifact(Class.forName(module[1], false, RuntimeIdentity.class.getClassLoader())));
			}
			catch (ClassNotFoundException ignored) {
				/* Optional adapter not on this classpath. */ }
		}
		return new RuntimeIdentity(revision, artifacts);
	}

	/**
	 * Default Judge source declaration, derived from the {@code agent-judge-core}
	 * actually loaded: {@code released:agent-judge:<version>} for a release,
	 * {@code snapshot:agent-judge:<version>} for a snapshot, and
	 * {@code unknown:agent-judge} when the loaded artifact carries no version.
	 * @return the declaration recorded when {@code experiment.judge.revision} is not set
	 */
	public static String loadedJudgeRevision() {
		String version = loadedVersion(io.github.markpollack.judge.verdict.Verdict.class, "io.github.markpollack",
				"agent-judge-core");
		if (version == null)
			return "unknown:agent-judge";
		// Maven's own snapshot test: a snapshot version ends in SNAPSHOT.
		return (version.endsWith("SNAPSHOT") ? "snapshot" : "released") + ":agent-judge:" + version;
	}

	/**
	 * Version from the loading jar's own pom.properties, or null for a class directory.
	 */
	static String loadedVersion(Class<?> type, String groupId, String artifactId) {
		try {
			Path location = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
			if (!Files.isRegularFile(location))
				return null;
			try (var jar = new java.util.jar.JarFile(location.toFile())) {
				var entry = jar.getJarEntry("META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties");
				if (entry == null)
					return null;
				var properties = new java.util.Properties();
				try (var in = jar.getInputStream(entry)) {
					properties.load(in);
				}
				String version = properties.getProperty("version");
				return version == null || version.isBlank() ? null : version.trim();
			}
		}
		catch (Exception e) {
			return null;
		}
	}

	/**
	 * Writer source declaration plus loaded binary identity; directory builds are
	 * explicitly named.
	 */
	public static RuntimeIdentity writer() {
		return new RuntimeIdentity(
				Objects.requireNonNull(System.getProperty("experiment.writer.revision"),
						"Set experiment.writer.revision or supply a writer identity"),
				Map.of("experiment-core", artifact(RuntimeIdentity.class)));
	}

	/**
	 * Hash a jar or a deterministically ordered class directory without using a context
	 * loader.
	 */
	public static String artifact(Class<?> type) {
		try {
			Path location = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
			if (Files.isRegularFile(location))
				return ProducerDocument.hash(Files.readAllBytes(location));
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (var paths = Files.walk(location)) {
				for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
					byte[] name = location.relativize(file).toString().getBytes(StandardCharsets.UTF_8);
					byte[] data = Files.readAllBytes(file);
					bytes.writeBytes((name.length + ":").getBytes(StandardCharsets.UTF_8));
					bytes.writeBytes(name);
					bytes.writeBytes((data.length + ":").getBytes(StandardCharsets.UTF_8));
					bytes.writeBytes(data);
				}
			}
			return "classes:" + ProducerDocument.hash(bytes.toByteArray());
		}
		catch (Exception e) {
			throw new IllegalStateException("Cannot identify loaded artifact " + type.getName(), e);
		}
	}
}
