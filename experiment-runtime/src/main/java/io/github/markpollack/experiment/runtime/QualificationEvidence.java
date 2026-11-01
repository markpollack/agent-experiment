package io.github.markpollack.experiment.runtime;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import io.github.markpollack.experiment.runtime.AgentRecords.*;

/**
 * Retains and checks Journal derivative references without interpreting native records.
 */
final class QualificationEvidence {

	private QualificationEvidence() {
	}

	static Qualification collect(FileRef original, Path directory, String state, String producer, String error) {
		List<FileRef> files = new ArrayList<>();
		String problem = error;
		if (Files.exists(directory)) {
			try (var paths = Files.walk(directory)) {
				for (Path path : paths.sorted().toList()) {
					if (Files.isSymbolicLink(path)) {
						problem += "; symbolic link refused: " + path;
						state = "REFUSED";
					}
					else if (Files.isRegularFile(path)) {
						try {
							String subject = path.equals(directory.resolve("acquisition.json"))
									? "qualified-acquisition-manifest"
									: path.equals(directory.resolve("assembly-expectation.json"))
											? "assembly-expectation" : "qualified-source-file";
							files.add(Evidence.reference(path, subject));
						}
						catch (IOException failure) {
							problem += "; unreadable " + path + ": " + failure;
							state = "REFUSED";
						}
					}
				}
			}
			catch (IOException failure) {
				problem += "; cannot inventory " + directory + ": " + failure;
				state = "REFUSED";
			}
		}
		var manifests = files.stream().filter(f -> f.subject().equals("qualified-acquisition-manifest")).toList();
		var expectations = files.stream().filter(f -> f.subject().equals("assembly-expectation")).toList();
		if (manifests.isEmpty() || expectations.isEmpty())
			state = "REFUSED";
		return new Qualification(original, state, directory.toAbsolutePath().normalize().toString(), manifests,
				expectations, files, producer, problem);
	}

	static void verify(Qualification q, Attempt attempt) throws IOException {
		AcquisitionAssembly.validateManifest(q.original(), attempt, true);
		Path root = Path.of(q.directory()).toAbsolutePath().normalize();
		for (FileRef f : q.retainedFiles()) {
			Path path = Path.of(f.path()).toAbsolutePath().normalize();
			if (!path.startsWith(root) || Files.isSymbolicLink(path)
					|| !path.toRealPath().startsWith(root.toRealPath()))
				throw new IOException("Qualification file outside retained directory");
			Evidence.verify(f);
		}
		if (!q.state().equals("QUALIFIED"))
			return; // refusal is evidence, not derivative acceptance
		var manifest = AcquisitionAssembly.validateManifest(q.manifests().getFirst(), attempt, true);
		if (!manifest.path("qualification").path("original_manifest_sha256").asText().equals(q.original().sha256()))
			throw new IOException("Wrong original-to-qualified manifest provenance");
		Path copy = root.resolve("client-acquisition.json");
		if (!q.retainedFiles()
			.stream()
			.anyMatch(f -> Path.of(f.path()).equals(copy) && f.sha256().equals(q.original().sha256())))
			throw new IOException("Original manifest copy missing or changed");
		var expectation = RuntimeJson.mapper().readTree(Path.of(q.assemblyExpectations().getFirst().path()).toFile());
		if (!expectation.path("kind").asText().equals("agent-journal/assembly-expectation")
				|| !expectation.path("version").asText().equals("0.1"))
			throw new IOException("Unsupported assembly expectation");
	}

}
