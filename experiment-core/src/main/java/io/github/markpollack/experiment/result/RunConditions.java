package io.github.markpollack.experiment.result;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The conditions a run was executed under, recorded at run time.
 *
 * <p>
 * This exists because a turn ceiling once governed two runs that were then compared as
 * though they had been permitted the same amount of work. The ceiling was not unknowable
 * — it was configured on the invoker, applied during the run, and dropped crossing into
 * {@link ExperimentResult}. A headline drawn from those two runs had to be withdrawn.
 *
 * <p>
 * <b>Why this is not another metadata map.</b> {@link ExperimentResult#metadata()}
 * already exists and is free-form, and it did not prevent that defect, because nothing
 * required it and nothing failed when it was empty. The difference here is
 * {@link #undeclaredBy()}: a component that declares nothing is <em>named</em>, the run
 * is marked {@link #complete() incomplete}, and the comparison engine refuses to compare
 * across it. The mark is load-bearing rather than documentary.
 *
 * <p>
 * Absence is recorded rather than fatal, deliberately. A hard failure that blocks a run
 * for a bookkeeping reason gets switched off under deadline; a mark that travels with the
 * data does not.
 *
 * @param values declared condition name to value, ordered for stable comparison
 * @param undeclaredBy components that declared no conditions; empty means complete
 */
public record RunConditions(Map<String, String> values, List<String> undeclaredBy) {

	public RunConditions {
		values = Map.copyOf(new TreeMap<>(values));
		undeclaredBy = List.copyOf(undeclaredBy);
	}

	/** No component has declared anything — the state a run starts in. */
	public static RunConditions undeclared(String... components) {
		return new RunConditions(Map.of(), List.of(components));
	}

	/** Every participating component declared its conditions. */
	public static RunConditions of(Map<String, String> values) {
		return new RunConditions(values, List.of());
	}

	/** Fold one component's declaration in, clearing it from the undeclared list. */
	public RunConditions declaring(String component, Map<String, String> declared) {
		Map<String, String> merged = new TreeMap<>(this.values);
		declared.forEach((k, v) -> merged.put(component + "." + k, v));
		List<String> remaining = this.undeclaredBy.stream().filter(c -> !c.equals(component)).toList();
		return new RunConditions(merged, declared.isEmpty() ? this.undeclaredBy : remaining);
	}

	/** True when nothing is outstanding. Only complete runs may be compared. */
	public boolean complete() {
		return undeclaredBy.isEmpty();
	}

	/**
	 * Why two runs are not comparable, or empty when they are.
	 *
	 * <p>
	 * Differing conditions are as disqualifying as absent ones: two runs permitted
	 * different amounts of work did not measure the same thing, which is the case this
	 * record was added for.
	 */
	public List<String> incomparabilityWith(RunConditions other) {
		if (!complete() || !other.complete()) {
			return List.of("conditions incomplete; undeclared by "
					+ String.join(", ", complete() ? other.undeclaredBy() : undeclaredBy()));
		}
		return values.keySet()
			.stream()
			.filter(k -> !java.util.Objects.equals(values.get(k), other.values().get(k)))
			.map(k -> "condition '" + k + "' differs: " + values.get(k) + " vs " + other.values().get(k))
			.toList();
	}

}
