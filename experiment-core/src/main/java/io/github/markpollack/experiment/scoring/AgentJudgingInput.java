package io.github.markpollack.experiment.scoring;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import io.github.markpollack.experiment.agent.TerminalStatus;
import org.jspecify.annotations.Nullable;

/**
 * Experiment agent evidence used by a per-item configured Jury factory; never passed to
 * Judge.
 */
public record AgentJudgingInput(String goal, @Nullable Path workspace, Duration executionTime, TerminalStatus status,
		Map<String, Object> metadata) {

	public AgentJudgingInput {
		metadata = Map.copyOf(metadata);
	}

	public static Builder builder() {
		return new Builder();
	}
	public static final class Builder {

		private String goal = "";

		private Path workspace;

		private Duration executionTime = Duration.ZERO;

		private TerminalStatus status = TerminalStatus.COMPLETED;

		private final Map<String, Object> metadata = new LinkedHashMap<>();

		public Builder goal(String value) {
			goal = value;
			return this;
		}

		public Builder workspace(Path value) {
			workspace = value;
			return this;
		}

		public Builder executionTime(Duration value) {
			executionTime = value;
			return this;
		}

		public Builder status(TerminalStatus value) {
			status = value;
			return this;
		}

		public Builder metadata(java.util.Map<String, Object> values) {
			metadata.putAll(values);
			return this;
		}

		public Builder metadata(String key, Object value) {
			metadata.put(key, value);
			return this;
		}

		public AgentJudgingInput build() {
			return new AgentJudgingInput(goal, workspace, executionTime, status, metadata);
		}

	}
}
