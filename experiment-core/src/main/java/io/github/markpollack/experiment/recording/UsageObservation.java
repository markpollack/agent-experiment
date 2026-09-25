package io.github.markpollack.experiment.recording;

import org.jspecify.annotations.Nullable;

/** Usage reported for one scoped observation; missing measurements stay null. */
public record UsageObservation(String observationId, String providerScope, String source, String scope,
		@Nullable Long inputTokens, @Nullable Long outputTokens, String totalDefinition, @Nullable Double cost,
		@Nullable String currency, @Nullable String priceRuleId) {
	public UsageObservation {
		for (String text : new String[] { observationId, providerScope, source, scope, totalDefinition })
			if (text == null || text.isBlank())
				throw new IllegalArgumentException("Usage identity/source/scope required");
		if (inputTokens != null && inputTokens < 0 || outputTokens != null && outputTokens < 0)
			throw new IllegalArgumentException("Negative tokens");
		if (cost != null && (!Double.isFinite(cost) || cost < 0 || currency == null || currency.isBlank()))
			throw new IllegalArgumentException("Invalid observed cost/currency");
		if (priceRuleId != null && (priceRuleId.isBlank() || cost == null || inputTokens == null || outputTokens == null
				|| !"input-plus-output:v1".equals(totalDefinition)))
			throw new IllegalArgumentException("Priced cost requires a named rule and complete applicable usage");
	}

	public String key() {
		return providerScope.length() + ":" + providerScope + observationId;
	}
}
