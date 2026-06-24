package io.github.markpollack.experiment.agent.claude;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import io.github.markpollack.claude.agent.sdk.config.PermissionMode;

/**
 * Configuration for {@link ClaudeSdkInvoker}. Captures Claude-specific settings that are
 * constant across invocations (not per-item).
 *
 * @param permissionMode Claude CLI permission mode
 * @param maxBudgetUsd maximum spend in USD per invocation (null = no limit)
 * @param maxTurns maximum conversation turns per invocation (null = no limit)
 * @param maxThinkingTokens extended thinking budget (null = disabled)
 * @param allowedTools tool names to allow via {@code --allowedTools} (empty = unset)
 * @param disallowedTools tool names to block via {@code --disallowedTools} (empty =
 * unset)
 * @param settingsPath path to a settings file passed via {@code --settings} (null =
 * unset)
 * @param extraArgs arbitrary passthrough CLI flags (flag name to value; empty = none)
 */
public record ClaudeSdkInvokerConfig(PermissionMode permissionMode, @Nullable Double maxBudgetUsd,
		@Nullable Integer maxTurns, @Nullable Integer maxThinkingTokens, List<String> allowedTools,
		List<String> disallowedTools, @Nullable String settingsPath, Map<String, String> extraArgs) {

	public ClaudeSdkInvokerConfig {
		java.util.Objects.requireNonNull(permissionMode, "permissionMode must not be null");
		allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
		disallowedTools = disallowedTools == null ? List.of() : List.copyOf(disallowedTools);
		extraArgs = extraArgs == null ? Map.of() : Map.copyOf(extraArgs);
	}

	/**
	 * Default config: skip permissions, no budget/turn/thinking limits, no tool/settings
	 * flags.
	 */
	public static ClaudeSdkInvokerConfig defaults() {
		return new ClaudeSdkInvokerConfig(PermissionMode.DANGEROUSLY_SKIP_PERMISSIONS, null, null, null, List.of(),
				List.of(), null, Map.of());
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private PermissionMode permissionMode = PermissionMode.DANGEROUSLY_SKIP_PERMISSIONS;

		private @Nullable Double maxBudgetUsd;

		private @Nullable Integer maxTurns;

		private @Nullable Integer maxThinkingTokens;

		private List<String> allowedTools = List.of();

		private List<String> disallowedTools = List.of();

		private @Nullable String settingsPath;

		private Map<String, String> extraArgs = Map.of();

		private Builder() {
		}

		public Builder permissionMode(PermissionMode permissionMode) {
			this.permissionMode = permissionMode;
			return this;
		}

		public Builder maxBudgetUsd(@Nullable Double maxBudgetUsd) {
			this.maxBudgetUsd = maxBudgetUsd;
			return this;
		}

		public Builder maxTurns(@Nullable Integer maxTurns) {
			this.maxTurns = maxTurns;
			return this;
		}

		public Builder maxThinkingTokens(@Nullable Integer maxThinkingTokens) {
			this.maxThinkingTokens = maxThinkingTokens;
			return this;
		}

		/** Tool names to allow via {@code --allowedTools}. Null is treated as empty. */
		public Builder allowedTools(@Nullable List<String> allowedTools) {
			this.allowedTools = allowedTools != null ? List.copyOf(allowedTools) : List.of();
			return this;
		}

		/**
		 * Tool names to block via {@code --disallowedTools}. Null is treated as empty.
		 */
		public Builder disallowedTools(@Nullable List<String> disallowedTools) {
			this.disallowedTools = disallowedTools != null ? List.copyOf(disallowedTools) : List.of();
			return this;
		}

		/** Path to a settings file passed via {@code --settings}. */
		public Builder settingsPath(@Nullable String settingsPath) {
			this.settingsPath = settingsPath;
			return this;
		}

		/**
		 * Arbitrary passthrough CLI flags (flag name to value). Null is treated as empty.
		 */
		public Builder extraArgs(@Nullable Map<String, String> extraArgs) {
			this.extraArgs = extraArgs != null ? Map.copyOf(extraArgs) : Map.of();
			return this;
		}

		public ClaudeSdkInvokerConfig build() {
			return new ClaudeSdkInvokerConfig(permissionMode, maxBudgetUsd, maxTurns, maxThinkingTokens, allowedTools,
					disallowedTools, settingsPath, extraArgs);
		}

	}

}
