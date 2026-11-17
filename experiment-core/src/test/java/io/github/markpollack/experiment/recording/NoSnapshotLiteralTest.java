package io.github.markpollack.experiment.recording;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shipped code must not name a snapshot as an identity: a released build would record it
 * as provenance. Versions are derived from the loaded artifacts instead.
 */
class NoSnapshotLiteralTest {

	@Test
	void noMainSourceStringLiteralNamesASnapshot() throws IOException {
		Path root = Path.of("").toAbsolutePath().getParent();
		List<Path> modules;
		try (var children = Files.list(root)) {
			modules = children.filter(p -> Files.isDirectory(p.resolve("src/main/java"))).sorted().toList();
		}
		assertThat(modules).extracting(p -> p.getFileName().toString())
			.contains("experiment-core", "experiment-agent-client", "experiment-claude", "experiment-workflow",
					"experiment-runtime");
		List<String> offenders = new ArrayList<>();
		int files = 0;
		for (Path module : modules) {
			try (var sources = Files.walk(module.resolve("src/main/java"))) {
				for (Path source : sources.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
					files++;
					for (String literal : literals(Files.readString(source)))
						if (literal.contains("-SNAPSHOT"))
							offenders.add(root.relativize(source) + ": \"" + literal + "\"");
				}
			}
		}
		assertThat(files).isGreaterThan(100);
		assertThat(offenders).isEmpty();
	}

	@Test
	void scannerFindsLiteralsAndSkipsComments() {
		String source = """
				// "1.0-SNAPSHOT" in a line comment
				/* "2.0-SNAPSHOT" in a block comment */
				/** {@code 3.0-SNAPSHOT} in Javadoc */
				String a = "x:4.0-SNAPSHOT", b = "esc\\"aped"; char c = '"';
				String d = \"""
				    text 5.0-SNAPSHOT
				    \""";
				""";
		assertThat(literals(source)).hasSize(3)
			.anySatisfy(l -> assertThat(l).isEqualTo("x:4.0-SNAPSHOT"))
			.anySatisfy(l -> assertThat(l).isEqualTo("esc\\\"aped"))
			.anySatisfy(l -> assertThat(l).contains("text 5.0-SNAPSHOT"));
	}

	@Test
	void defaultJudgeRevisionFollowsTheLoadedJudge() throws IOException {
		var properties = new Properties();
		try (var in = getClass()
			.getResourceAsStream("/META-INF/maven/io.github.markpollack/agent-judge-core/pom.properties")) {
			assertThat(in).isNotNull();
			properties.load(in);
		}
		String version = properties.getProperty("version");
		assertThat(RuntimeIdentity.loadedJudgeRevision())
			.isEqualTo((version.endsWith("SNAPSHOT") ? "snapshot" : "released") + ":agent-judge:" + version);
	}

	/** String and text-block literal contents, skipping comments and char literals. */
	static List<String> literals(String source) {
		List<String> found = new ArrayList<>();
		int i = 0;
		int n = source.length();
		while (i < n) {
			char c = source.charAt(i);
			if (source.startsWith("//", i)) {
				int end = source.indexOf('\n', i);
				i = end < 0 ? n : end + 1;
			}
			else if (source.startsWith("/*", i)) {
				int end = source.indexOf("*/", i + 2);
				i = end < 0 ? n : end + 2;
			}
			else if (source.startsWith("\"\"\"", i)) {
				int j = i + 3;
				while (j < n && !source.startsWith("\"\"\"", j))
					j += source.charAt(j) == '\\' ? 2 : 1;
				found.add(source.substring(i + 3, Math.min(j, n)));
				i = j + 3;
			}
			else if (c == '"' || c == '\'') {
				int j = i + 1;
				while (j < n && source.charAt(j) != c)
					j += source.charAt(j) == '\\' ? 2 : 1;
				if (c == '"')
					found.add(source.substring(i + 1, Math.min(j, n)));
				i = j + 1;
			}
			else
				i++;
		}
		return found;
	}

}
