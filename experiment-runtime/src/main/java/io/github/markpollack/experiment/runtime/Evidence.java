package io.github.markpollack.experiment.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import io.github.markpollack.experiment.runtime.AgentRecords.*;

/**
 * Explicit content-integrity checks; these do not establish source semantic verification.
 */
public final class Evidence {

	private Evidence() {
	}

	public static String sha256(byte[] bytes) {
		return HexFormat.of().formatHex(digest().digest(bytes));
	}

	public static String sha256(Path file) throws IOException {
		MessageDigest digest = digest();
		try (InputStream in = Files.newInputStream(file)) {
			byte[] buffer = new byte[8192];
			int n;
			while ((n = in.read(buffer)) >= 0)
				digest.update(buffer, 0, n);
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	public static FileRef reference(Path file, String subject) throws IOException {
		return new FileRef(file.toAbsolutePath().normalize().toString(), sha256(file), subject);
	}

	public static void verify(FileRef ref) throws IOException {
		if (!sha256(Path.of(ref.path())).equals(ref.sha256()))
			throw new IOException("Hash mismatch: " + ref.subject());
	}

	public static void verify(CanonicalRef canonical) throws IOException {
		for (FileRef file : canonical.retainedFiles())
			verify(file);
	}

	/**
	 * Call before treating retained references as available evidence; never implied by
	 * load().
	 */
	public static void verify(Row row) throws IOException {
		for (Invocation invocation : row.invocations()) {
			for (FileRef manifest : invocation.acquisitions())
				AcquisitionAssembly.validateManifest(manifest, invocation.attempt(),
						invocation.captureState().equals("COMPLETE"));
			for (Qualification q : invocation.qualifications())
				QualificationEvidence.verify(q, invocation.attempt());
			for (CanonicalRef canonical : invocation.canonical())
				verify(canonical);
		}
		for (WorkflowProof proof : row.workflowProof()) {
			verify(proof.snapshot());
			var snapshot = RuntimeJson.mapper().readTree(Path.of(proof.snapshot().path()).toFile());
			if (!snapshot.path("runId").asText().equals(proof.runId())
					|| !snapshot.path("status").asText().equals(proof.state()))
				throw new IOException("Workflow snapshot identity/state mismatch");
			for (Attempt attempt : proof.attempts()) {
				boolean found = false;
				for (var logical : snapshot.path("invocations")) {
					if (!logical.path("invocationId").asText().equals(attempt.workflowInvocationId()))
						continue;
					for (var physical : logical.path("attempts")) {
						if (physical.path("attemptId").asText().equals(attempt.workflowAttemptId())
								&& physical.path("number").asInt() == attempt.attemptNumber()) {
							if (proof.state().equals("SUCCEEDED")
									&& !physical.path("disposition").asText().equals("COMMITTED"))
								throw new IOException("Uncommitted Workflow output");
							found = true;
						}
					}
				}
				if (!found)
					throw new IOException("Wrong Workflow attempt in snapshot");
			}
		}
		for (FileRef artifact : row.domain().artifacts())
			verify(artifact);
	}

	private static MessageDigest digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

}
