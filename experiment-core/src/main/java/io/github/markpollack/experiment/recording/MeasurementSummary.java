package io.github.markpollack.experiment.recording;

import java.util.*;
import java.math.BigInteger;
import org.jspecify.annotations.Nullable;

/**
 * Complete totals are absent whenever coverage is missing, incompatible or conflicted.
 */
public record MeasurementSummary(TokenSummary tokens, CostSummary costUsd) {
	public record TokenSummary(@Nullable Long total, @Nullable Long knownSubtotal, int measured, int unknown,
			int conflicted) {
	}

	public record CostSummary(@Nullable Double total, @Nullable Double knownSubtotal, int measured, int unknown,
			int conflicted) {
	}

	public static MeasurementSummary of(List<InvocationObservation> observations) {
		Map<String, UsageObservation> unique = new LinkedHashMap<>();
		Set<String> conflicts = new HashSet<>();
		int empty = 0;
		for (InvocationObservation observation : observations) {
			if (observation.usage().isEmpty())
				empty++;
			for (UsageObservation usage : observation.usage()) {
				UsageObservation prior = unique.putIfAbsent(usage.key(), usage);
				if (prior != null && !prior.equals(usage))
					conflicts.add(usage.key());
			}
		}
		BigInteger tokens = BigInteger.ZERO;
		double cost = 0;
		int tm = 0, tu = empty, cm = 0, cu = empty;
		for (var entry : unique.entrySet()) {
			if (conflicts.contains(entry.getKey()))
				continue;
			UsageObservation u = entry.getValue();
			boolean compatible = "input-plus-output:v1".equals(u.totalDefinition())
					&& "adapter-request".equals(u.scope());
			if (compatible) {
				if (u.inputTokens() != null)
					tokens = tokens.add(BigInteger.valueOf(u.inputTokens()));
				if (u.outputTokens() != null)
					tokens = tokens.add(BigInteger.valueOf(u.outputTokens()));
			}
			if (compatible && u.inputTokens() != null && u.outputTokens() != null)
				tm++;
			else
				tu++;
			if (u.cost() != null && "USD".equals(u.currency()) && compatible) {
				cost += u.cost();
				cm++;
			}
			else
				cu++;
		}
		Long subtotal = tokens.bitLength() > 63 ? null : tokens.longValue();
		Double costSubtotal = Double.isFinite(cost) ? cost : null;
		Long total = tm > 0 && tu == 0 && conflicts.isEmpty() ? subtotal : null;
		Double totalCost = cm > 0 && cu == 0 && conflicts.isEmpty() ? costSubtotal : null;
		return new MeasurementSummary(new TokenSummary(total, subtotal, tm, tu, conflicts.size()),
				new CostSummary(totalCost, costSubtotal, cm, cu, conflicts.size()));
	}
}
