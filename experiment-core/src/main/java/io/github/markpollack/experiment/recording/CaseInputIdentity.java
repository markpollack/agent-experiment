package io.github.markpollack.experiment.recording;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Caller-declared identity of the actual input, excluding the candidate configuration.
 */
public record CaseInputIdentity(String namespace, String id, @Nullable String revision, @Nullable String sha256,
		String coverage) {
	public CaseInputIdentity {
		if (namespace == null || namespace.isBlank() || id == null || id.isBlank()
				|| !"effective-case-input:v1".equals(coverage))
			throw new IllegalArgumentException("Invalid effective-input identity");
		if (revision != null && revision.isBlank())
			throw new IllegalArgumentException("Blank input revision");
		if (sha256 != null && !sha256.matches("[0-9a-f]{64}"))
			throw new IllegalArgumentException("Invalid input digest");
		if (revision == null && sha256 == null)
			throw new IllegalArgumentException("Input identity needs revision or digest");
	}

	/** MATCH, INCOMPATIBLE or UNKNOWN; absence never proves equality. */
	public static String compatibility(@Nullable CaseInputIdentity a, @Nullable CaseInputIdentity b) {
		if (a == null || b == null)
			return "UNKNOWN";
		if (!a.namespace.equals(b.namespace) || !a.id.equals(b.id) || !a.coverage.equals(b.coverage))
			return "INCOMPATIBLE";
		if (a.revision != null && b.revision != null && !a.revision.equals(b.revision)
				|| a.sha256 != null && b.sha256 != null && !a.sha256.equals(b.sha256))
			return "INCOMPATIBLE";
		if ((a.revision == null) != (b.revision == null) || (a.sha256 == null) != (b.sha256 == null))
			return "UNKNOWN";
		return "MATCH";
	}
}
