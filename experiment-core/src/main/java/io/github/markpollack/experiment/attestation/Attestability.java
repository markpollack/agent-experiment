package io.github.markpollack.experiment.attestation;

/**
 * How far a stored item's outcome can be traced to the judges that produced it.
 *
 * <p>
 * A pass rate is a count, and a count is only as good as its denominator. For a jury
 * verdict that means two numbers: how many judges voted, and how many should have. The
 * first is recorded by agent-judge's aggregation evidence; the second is the jury as it
 * was configured. A jury that silently votes with fewer judges than it lists produces a
 * plausible verdict either way, so the difference is only visible when both are stored.
 *
 * <p>
 * Classification is weakest-link: an item is only as attested as the least attested jury
 * that contributed to it, because a cascade decided by a tier whose votes went unrecorded
 * has an unrecorded denominator whatever the other tiers recorded.
 */
public enum Attestability {

	/** No verdict was recorded; the item never reached a jury. */
	NOT_JUDGED,

	/**
	 * A verdict exists, but at least one contributing jury recorded no vote counts — it
	 * failed to execute, errored without evidence, or predates aggregation evidence — so
	 * how many judges voted cannot be established.
	 */
	NO_VOTE_EVIDENCE,

	/**
	 * Every contributing jury recorded how many judges voted, but no configured roster
	 * was recorded, so whether that is how many should have voted cannot be established.
	 */
	VOTES_WITHOUT_ROSTER

}
