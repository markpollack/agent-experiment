package io.github.markpollack.experiment.agent;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Context provided to an {@link AgentInvoker} for a single item invocation.
 *
 * @param workspacePath directory where the agent operates
 * @param prompt fully constructed prompt
 * @param systemPrompt optional system prompt appended to agent's base prompt
 * @param model model identifier (e.g., "sonnet", "opus")
 * @param timeout timeout hint for the invoker
 * @param metadata pass-through metadata (experimentId, itemId, etc.)
 * @param runDir optional directory for run artifacts (trace files, logs)
 */
public record InvocationContext(Path workspacePath, String prompt, @Nullable String systemPrompt, String model,
		Duration timeout, Map<String, String> metadata, @Nullable Path runDir) {

	public InvocationContext {
		java.util.Objects.requireNonNull(workspacePath, "workspacePath must not be null");
		java.util.Objects.requireNonNull(prompt, "prompt must not be null");
		java.util.Objects.requireNonNull(model, "model must not be null");
		java.util.Objects.requireNonNull(timeout, "timeout must not be null");
		metadata = Map.copyOf(metadata);
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private Path workspacePath;

		private String prompt;

		private @Nullable String systemPrompt;

		private String model;

		private Duration timeout;

		private Map<String, String> metadata = Map.of();

		private @Nullable Path runDir;

		private Builder() {
		}

		public Builder workspacePath(Path workspacePath) {
			this.workspacePath = workspacePath;
			return this;
		}

		public Builder prompt(String prompt) {
			this.prompt = prompt;
			return this;
		}

		public Builder systemPrompt(@Nullable String systemPrompt) {
			this.systemPrompt = systemPrompt;
			return this;
		}

		public Builder model(String model) {
			this.model = model;
			return this;
		}

		public Builder timeout(Duration timeout) {
			this.timeout = timeout;
			return this;
		}

		public Builder metadata(Map<String, String> metadata) {
			this.metadata = metadata;
			return this;
		}

		public Builder runDir(@Nullable Path runDir) {
			this.runDir = runDir;
			return this;
		}

		public InvocationContext build() {
			return new InvocationContext(workspacePath, prompt, systemPrompt, model, timeout, metadata, runDir);
		}

	}

}
