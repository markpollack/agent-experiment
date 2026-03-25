package io.github.markpollack.experiment.result;

/**
 * A single file entry within a {@link KnowledgeManifest}.
 *
 * @param relativePath path relative to the knowledge base root
 * @param sizeBytes file size in bytes
 */
public record KnowledgeFileEntry(String relativePath, long sizeBytes) {

	public KnowledgeFileEntry {
		java.util.Objects.requireNonNull(relativePath, "relativePath must not be null");
		if (sizeBytes < 0) {
			throw new IllegalArgumentException("sizeBytes must not be negative");
		}
	}

}
