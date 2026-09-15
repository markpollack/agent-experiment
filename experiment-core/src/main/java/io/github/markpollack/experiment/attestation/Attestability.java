package io.github.markpollack.experiment.attestation;

/**
 * How far a stored item's outcome can be traced to the judges that produced it.
 *
 * <p>
 * A pass rate is a count, and a count is only as good as its denominator. For a jury
 * verdict that means two numbers: how many judges voted, and how many should have. The
 * first is recorded by agent-judge's aggregation evidence on every verdict; the second is
 * the jury as it was configured, recorded as the run's instrument. A jury that silently
 * votes with fewer judges than it lists produces a plausible verdict either way, so the
 * difference is only visible when both are stored.
 *
 * <p>
 * Classification is weakest-link: an item is only as attested as the least attested jury
 * that contributed to it, because a cascade decided by a tier whose votes went unrecorded
 * has an unrecorded denominator whatever the other tiers recorded. A contradiction
 * outranks everything else, since it is a demonstrated defect rather than a gap.
 */
public enum Attestability {

	/** No verdict was recorded; the item never reached a jury. */
	NOT_JUDGED,

	/**
	 * At least one contributing jury counted a different number of votes than its
	 * recorded roster lists. The instrument did not score with the judges it was
	 * configured with.
	 */
	ROSTER_MISMATCH,

	/**
	 * A verdict exists, but at least one contributing jury recorded no vote counts — it
	 * failed to execute, errored without evidence, or predates aggregation evidence — so
	 * how many judges voted cannot be established.
	 */
	NO_VOTE_EVIDENCE,

	/**
	 * Every contributing jury recorded how many judges voted, but at least one has no
	 * recorded roster to check that against: the run recorded no instrument, the jury
	 * could not be described, or the verdict was scored by a different instrument than
	 * the one its run records.
	 */
	VOTES_WITHOUT_ROSTER,

	/**
	 * Every contributing jury recorded how many judges voted, and each count matches the
	 * roster of the instrument that produced the verdict.
	 */
	ATTESTED

}
