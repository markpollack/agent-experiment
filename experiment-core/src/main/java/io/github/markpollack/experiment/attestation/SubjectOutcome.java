package io.github.markpollack.experiment.attestation;

/**
 * What one item says about the subject that was evaluated — never about the instrument
 * that evaluated it.
 *
 * <p>
 * An instrument failure is counted separately and can accompany any of these, because an
 * item can carry a real rejection and a broken instrument at once.
 */
public enum SubjectOutcome {

	/** The jury passed the subject. */
	PASS,

	/**
	 * The jury decided against the subject: it failed, the judge could not decide on a
	 * criterion that applied, or a tier stopped on one judge's rejection.
	 */
	NON_PASS,

	/**
	 * Nothing was decided about the subject: the criteria did not apply, or the
	 * instrument could not score it. Excluded from the denominator, and counted.
	 */
	EXCLUDED,

	/** The item never reached a jury. */
	NOT_JUDGED,

	/**
	 * What the jury decided cannot be established from what was recorded: no stopping
	 * decision, a decision naming a stage the file does not contain, a stage whose
	 * disposition was never recorded, or a status this version cannot read.
	 *
	 * <p>
	 * Excluded from the denominator and counted, never resolved by reading an aggregate's
	 * shape. Inferring an outcome from a missing fact is the defect this whole record
	 * exists to prevent.
	 */
	UNATTESTABLE

}
