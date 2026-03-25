package io.github.markpollack.experiment.store;

/**
 * Thrown when a result cannot be saved to or loaded from the result store. Causes include
 * I/O errors, serialization failures, or corrupt result files.
 */
public class ResultStoreException extends RuntimeException {

	public ResultStoreException(String message) {
		super(message);
	}

	public ResultStoreException(String message, Throwable cause) {
		super(message, cause);
	}

}
