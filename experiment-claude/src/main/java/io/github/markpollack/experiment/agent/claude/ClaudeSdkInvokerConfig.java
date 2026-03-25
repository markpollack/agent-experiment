package io.github.markpollack.experiment.agent.claude;

import org.jspecify.annotations.Nullable;
import org.springaicommunity.claude.agent.sdk.config.PermissionMode;

/**
 * Configuration for {@link ClaudeSdkInvoker}. Captures Claude-specific settings that are
 * constant across invocations (not per-item).
 *
 * @param permissionMode Claude CLI permission mode
 * @param maxBudgetUsd maximum spend in USD per invocation (null = no limit)
 * @param maxTurns maximum conversation turns per invocation (null = no limit)
 * @param maxThinkingTokens extended thinking budget (null = disabled)
 */
public record ClaudeSdkInvokerConfig(PermissionMode permissionMode, @Nullable Double maxBudgetUsd,
		@Nullable Integer maxTurns, @Nullable Integer maxThinkingTokens) {

	public ClaudeSdkInvokerConfig {
		java.util.Objects.requireNonNull(permissionMode, "permissionMode must not be null");
	}

	/** Default config: skip permissions, no budget/turn/thinking limits. */
	public static ClaudeSdkInvokerConfig defaults() {
		return new ClaudeSdkInvokerConfig(PermissionMode.DANGEROUSLY_SKIP_PERMISSIONS, null, null, null);
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private PermissionMode permissionMode = PermissionMode.DANGEROUSLY_SKIP_PERMISSIONS;

		private @Nullable Double maxBudgetUsd;

		private @Nullable Integer maxTurns;

		private @Nullable Integer maxThinkingTokens;

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

		public ClaudeSdkInvokerConfig build() {
			return new ClaudeSdkInvokerConfig(permissionMode, maxBudgetUsd, maxTurns, maxThinkingTokens);
		}

	}

}
