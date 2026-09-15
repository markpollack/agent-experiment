package io.github.markpollack.experiment.result;

import java.util.OptionalDouble;

/**
 * What a run observed, counted: the parts a pass rate is made of.
 *
 * <p>
 * A rate is stored nowhere. A rate is a derivation, and a derivation hides the
 * denominator it divided by — which is how an instrument's failure came to be recorded as
 * an agent's. These counts are facts the run observed, and any rate a reader wants is
 * arithmetic over them.
 *
 * <p>
 * Each item lands in exactly one of {@code passes}, {@code nonPasses}, {@code excluded}
 * and {@code notJudged}. {@code instrumentFailures} counts something different and
 * overlaps them deliberately: an item can be a genuine rejection <em>and</em> carry a
 * broken instrument, and one number cannot say both.
 *
 * @param passes items the jury passed
 * @param nonPasses items the jury decided against the subject: a failure, an abstention,
 * or a tier that stopped on one judge's rejection
 * @param excluded items that left the denominator: the criteria did not apply, or the
 * instrument could not score them
 * @param instrumentFailures items whose instrument failed, whatever the subject outcome
 * @param notJudged items that never reached a jury
 * @param unattestable items whose outcome cannot be established from what was recorded
 */
public record ItemCounts(int passes, int nonPasses, int excluded, int instrumentFailures, int notJudged,
		int unattestable) {

	public ItemCounts {
		if (passes < 0 || nonPasses < 0 || excluded < 0 || instrumentFailures < 0 || notJudged < 0
				|| unattestable < 0) {
			throw new IllegalArgumentException("counts must not be negative");
		}
	}

	/** Items the jury scored, which is the denominator of a pass rate. */
	public int scored() {
		return this.passes + this.nonPasses;
	}

	/** Every item the run produced. */
	public int total() {
		return this.passes + this.nonPasses + this.excluded + this.notJudged + this.unattestable;
	}

	/**
	 * Passes over items the jury scored, or empty when it scored none.
	 *
	 * <p>
	 * An empty denominator has no rate. It is not 0.0: a run where every item was
	 * excluded did not fail, it measured nothing, and reporting zero would be the same
	 * mistake as counting a broken judge as a failed agent.
	 */
	public OptionalDouble passRate() {
		return scored() == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) this.passes / scored());
	}

}
