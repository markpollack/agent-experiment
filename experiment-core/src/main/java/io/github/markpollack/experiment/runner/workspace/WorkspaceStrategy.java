package io.github.markpollack.experiment.runner.workspace;

/**
 * How {@link GitWorkspaceProvisioner} materializes a git {@code SourceRef} into a
 * workspace.
 */
public enum WorkspaceStrategy {

	/**
	 * Maintain one persistent clone per source repository and reuse it across items,
	 * resetting it to the target commit between items. Keeps any external index (e.g. an
	 * IDE/LSP project index) warm across items. This is the default and the only strategy
	 * compatible with IntelliJ project import.
	 */
	CLONE,

	/**
	 * Create a fresh {@code git worktree} per item from a shared clone, removing it on
	 * release. Faster checkout but known to stall IntelliJ project import — avoid when an
	 * IDE/LSP index must track the workspace.
	 */
	WORKTREE

}
