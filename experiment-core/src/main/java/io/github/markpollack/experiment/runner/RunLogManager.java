package io.github.markpollack.experiment.runner;

import java.nio.file.Files;
import java.nio.file.Path;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Programmatically attaches a Logback {@code FileAppender} to the root logger for the
 * duration of an experiment run. Uses direct Logback API with graceful degradation when
 * Logback isn't on the classpath.
 *
 * <p>
 * All Logback references are isolated in inner classes ({@code LogbackAttacher},
 * {@code LogbackDetacher}) so the JVM only attempts to link them when logback is actually
 * present. This avoids forcing logback on experiment-core consumers that use a different
 * SLF4J backend.
 */
final class RunLogManager {

	private static final Logger logger = LoggerFactory.getLogger(RunLogManager.class);

	private RunLogManager() {
	}

	/**
	 * Attach a file appender writing to {@code {runDir}/run.log}.
	 * @param runDir the run artifact directory
	 * @return an opaque handle to pass to {@link #detach(Object)}, or null if attachment
	 * failed
	 */
	static @Nullable Object attach(Path runDir) {
		try {
			Files.createDirectories(runDir);
			return LogbackAttacher.attach(runDir);
		}
		catch (NoClassDefFoundError ex) {
			logger.debug("Logback not on classpath, run log disabled");
			return null;
		}
		catch (Exception ex) {
			logger.warn("Failed to attach run log to {}: {}", runDir, ex.getMessage());
			return null;
		}
	}

	/**
	 * Detach a previously attached run log appender.
	 * @param handle the handle returned by {@link #attach(Path)}, or null (no-op)
	 */
	static void detach(@Nullable Object handle) {
		if (handle == null) {
			return;
		}
		try {
			LogbackDetacher.detach(handle);
		}
		catch (NoClassDefFoundError ex) {
			// Logback not on classpath — nothing to detach
		}
		catch (Exception ex) {
			logger.warn("Failed to detach run log: {}", ex.getMessage());
		}
	}

	/**
	 * Inner class isolating all Logback references for attach. The JVM only loads and
	 * links this class when {@link #attach(Path)} is called, so the
	 * {@code NoClassDefFoundError} catch in the outer method works correctly.
	 */
	private static final class LogbackAttacher {

		static Object attach(Path runDir) {
			Path logFile = runDir.resolve("run.log");

			ch.qos.logback.classic.LoggerContext context = (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory
				.getILoggerFactory();

			ch.qos.logback.classic.encoder.PatternLayoutEncoder encoder = new ch.qos.logback.classic.encoder.PatternLayoutEncoder();
			encoder.setContext(context);
			encoder.setPattern("%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
			encoder.start();

			ch.qos.logback.core.FileAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.FileAppender<>();
			appender.setContext(context);
			appender.setName("experiment-run-log");
			appender.setFile(logFile.toString());
			appender.setEncoder(encoder);
			appender.start();

			ch.qos.logback.classic.Logger rootLogger = context
				.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
			rootLogger.addAppender(appender);

			logger.info("Run log attached: {}", logFile);
			return appender;
		}

	}

	/**
	 * Inner class isolating all Logback references for detach.
	 */
	private static final class LogbackDetacher {

		@SuppressWarnings("unchecked")
		static void detach(Object handle) {
			ch.qos.logback.core.FileAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = (ch.qos.logback.core.FileAppender<ch.qos.logback.classic.spi.ILoggingEvent>) handle;

			ch.qos.logback.classic.LoggerContext context = (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory
				.getILoggerFactory();
			ch.qos.logback.classic.Logger rootLogger = context
				.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
			rootLogger.detachAppender(appender);
			appender.stop();

			logger.debug("Run log detached");
		}

	}

}
