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
		String revision = System.getProperty("experiment.judge.revision", "released:agent-judge:0.18.0");
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
