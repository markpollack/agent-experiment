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

	private static MessageDigest digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

}
