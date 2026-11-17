package io.github.markpollack.experiment.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.jar.JarFile;

/**
 * Versions of the artifacts actually loaded, read from their own build metadata, so that
 * recorded identities name what ran: a released build records released coordinates and a
 * snapshot build records its snapshot, with no source edit between them.
 */
public final class LoadedArtifacts {

	/** Recorded when the loaded artifact carries no readable version. */
	public static final String UNKNOWN = "unknown";

	private LoadedArtifacts() {
	}

	/**
	 * Maven version of the artifact that loaded {@code type}, from that artifact's own
	 * {@code META-INF/maven/<group>/<artifact>/pom.properties}.
	 * @param type a class from the artifact
	 * @param groupId the artifact's group
	 * @param artifactId the artifact's id
	 * @return its version, or {@link #UNKNOWN} when the class was loaded from a location
	 * without that metadata, such as a class directory
	 */
	public static String version(Class<?> type, String groupId, String artifactId) {
		String entry = "META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties";
		try {
			var source = type.getProtectionDomain().getCodeSource();
			if (source == null || source.getLocation() == null)
				return UNKNOWN;
			Path location = Path.of(source.getLocation().toURI());
			if (Files.isRegularFile(location)) {
				try (var jar = new JarFile(location.toFile())) {
					var found = jar.getJarEntry(entry);
					if (found == null)
						return UNKNOWN;
					try (InputStream in = jar.getInputStream(found)) {
						return versionOf(in);
					}
				}
			}
			Path properties = location.resolve(entry);
			if (!Files.isRegularFile(properties))
				return UNKNOWN;
			try (InputStream in = Files.newInputStream(properties)) {
				return versionOf(in);
			}
		}
		catch (IOException | URISyntaxException | RuntimeException e) {
			return UNKNOWN;
		}
	}

	/**
	 * {@code artifactId:version} of the artifact that loaded {@code type}.
	 * @param type a class from the artifact
	 * @param groupId the artifact's group
	 * @param artifactId the artifact's id
	 * @return the recorded identity
	 */
	public static String identity(Class<?> type, String groupId, String artifactId) {
		return artifactId + ":" + version(type, groupId, artifactId);
	}

	/**
	 * The version of this Experiment build, filtered into a resource when it was built.
	 * @return the build version
	 * @throws IllegalStateException if the build did not supply it
	 */
	public static String experimentVersion() {
		try (InputStream in = LoadedArtifacts.class.getResourceAsStream("experiment-version.properties")) {
			String version = in == null ? UNKNOWN : versionOf(in);
			if (UNKNOWN.equals(version))
				throw new IllegalStateException("This Experiment build carries no build version");
			return version;
		}
		catch (IOException e) {
			throw new IllegalStateException("Cannot read this Experiment build's version", e);
		}
	}

	private static String versionOf(InputStream in) throws IOException {
		var properties = new Properties();
		properties.load(in);
		String version = properties.getProperty("version");
		return version == null || version.isBlank() || version.contains("${") ? UNKNOWN : version.trim();
	}

}
