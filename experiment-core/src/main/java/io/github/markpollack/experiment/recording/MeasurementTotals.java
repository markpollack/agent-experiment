package io.github.markpollack.experiment.recording;

import java.util.List;
import io.github.markpollack.experiment.result.ItemResult;
import org.jspecify.annotations.Nullable;

/** Compatible complete totals for existing Agent observations. */
public final class MeasurementTotals {

	private MeasurementTotals() {
	}

	public static @Nullable Long tokens(List<ItemResult> items) {
		if (items.isEmpty())
			return null;
		long total = 0;
		try {
			for (ItemResult item : items) {
				if (item.totalTokens() == null)
					return null;
				total = Math.addExact(total, item.totalTokens());
			}
			return total;
		}
		catch (ArithmeticException overflow) {
			return null;
		}
	}

	public static @Nullable Double cost(List<ItemResult> items) {
		if (items.isEmpty())
			return null;
		double total = 0;
		for (ItemResult item : items) {
			if (item.costUsd() == null)
				return null;
			total += item.costUsd();
		}
		return Double.isFinite(total) ? total : null;
	}

}
