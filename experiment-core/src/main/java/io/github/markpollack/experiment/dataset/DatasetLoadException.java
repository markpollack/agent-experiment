package io.github.markpollack.experiment.dataset;

/**
 * Thrown when a dataset cannot be loaded from the filesystem. Causes include missing or
 * malformed {@code dataset.json}, unsupported schema version, or I/O errors reading
 * fixture files.
 */
public class DatasetLoadException extends RuntimeException {

	public DatasetLoadException(String message) {
		super(message);
	}

	public DatasetLoadException(String message, Throwable cause) {
		super(message, cause);
	}

}
