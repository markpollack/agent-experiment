package io.github.markpollack.experiment.runtime;

import java.util.List;
import io.github.markpollack.experiment.runtime.AgentRecords.*;

/**
 * Finalizes a row the Experiment caller executed itself, without Workflow. This is the
 * direct counterpart of {@link WorkflowAdapter#assess}: the row states
 * {@link RowExecution#DIRECT} and carries no Workflow proof, and no Workflow is created
 * around the invocation.
 */
public final class DirectExecution {

	private DirectExecution() {
	}

	/**
	 * Runs this item's Jury once when the direct execution completed with its workspace
	 * retained. Otherwise the Jury is not run and the row is recorded as not assessed,
	 * with its invocations, measurements and workspace state retained.
	 * @param prepared the prepared item; its identity is the row's identity
	 * @param state {@code COMPLETED}, {@code CANCELLED} or {@code INCOMPLETE}, as
	 * observed
	 * @param invocations the row's direct attempts, each with its own outcome
	 * @param workspaceState {@code RETAINED}, or {@code QUARANTINED} when a provider
	 * drain is unconfirmed
	 * @param measured the domain measurements taken after execution
	 * @param factory builds this item's Jury
	 * @return the row to save
	 */
	public static Row assess(PreparedItem prepared, String state, List<Invocation> invocations, String workspaceState,
			Domain measured, JudgeRecording.ItemJuryFactory factory) {
		for (Invocation invocation : invocations) {
			AgentRecords.require(invocation.attempt().row().equals(prepared.identity()), "Prepared/row mismatch");
			AgentRecords.require(invocation.attempt().execution() == ExecutionKind.DIRECT,
					"Workflow attempt in a direct row");
		}
		if (!state.equals("COMPLETED") || !workspaceState.equals("RETAINED"))
			return Row.direct(prepared.identity(), invocations, state, measured, List.of(), "NOT_ASSESSED",
					"Direct execution did not complete with its workspace retained", workspaceState);
		// JudgeRecording checks every row rule before it creates the Jury.
		return JudgeRecording.assess(prepared, invocations, RowExecution.DIRECT, state, measured, factory, List.of());
	}

}
