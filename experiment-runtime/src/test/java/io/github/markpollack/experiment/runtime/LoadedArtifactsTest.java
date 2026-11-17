package io.github.markpollack.experiment.runtime;

import java.io.IOException;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoadedArtifactsTest {

	@Test
	void experimentVersionIsThisBuildsVersion() {
		assertThat(LoadedArtifacts.experimentVersion()).isEqualTo(System.getProperty("experiment.build.version"))
			.doesNotContain("${");
	}

	@Test
	void journalIdentitiesNameTheLoadedJars() throws IOException {
		assertThat(AcquisitionAssembly.writerIdentity())
			.isEqualTo("journal-ocsf-writer:" + declared("journal-ocsf-writer"));
		assertThat(AcquisitionAssembly.qualifierIdentity())
			.isEqualTo("journal-capture-tck:" + declared("journal-capture-tck"));
		assertThat(AcquisitionAssembly.writerIdentity()).doesNotContain("fresh-v3")
			.doesNotContain(LoadedArtifacts.UNKNOWN);
	}

	@Test
	void aClassDirectoryHasNoVersion() {
		assertThat(LoadedArtifacts.version(LoadedArtifactsTest.class, "io.github.markpollack", "experiment-runtime"))
			.isEqualTo(LoadedArtifacts.UNKNOWN);
	}

	private static String declared(String artifactId) throws IOException {
		var properties = new Properties();
		try (var in = LoadedArtifactsTest.class
			.getResourceAsStream("/META-INF/maven/io.github.markpollack/" + artifactId + "/pom.properties")) {
			assertThat(in).isNotNull();
			properties.load(in);
		}
		return properties.getProperty("version");
	}

}
