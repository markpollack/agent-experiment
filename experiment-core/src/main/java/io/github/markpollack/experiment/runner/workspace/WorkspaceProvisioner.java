package io.github.markpollack.experiment.runner.workspace;

import java.io.IOException;
import java.nio.file.Path;

import io.github.markpollack.experiment.dataset.ResolvedItem;

/**
 * Materializes the working directory an agent operates in for a single dataset item.
 *
 * <p>
 * This is the seam that lets the runner support both file-fixture items (a physical
 * {@code before/} directory copied into a temp workspace) and repo-scale items (a
 * {@link io.github.markpollack.experiment.dataset.SourceRef} checked out from git). The
 * runner provisions a workspace before invocation and releases it afterward; the
 * provisioner decides whether the workspace is ephemeral (deleted on release) or a reused
 * checkout kept warm across items.
 *
 * <p>
 * Implementations are used sequentially across items within a single experiment and need
 * not be thread-safe.
 */
public interface WorkspaceProvisioner extends AutoCloseable {

	/**
	 * Provision the working directory for an item. Called once per item before the agent
	 * is invoked.
	 * @param resolved the resolved item (physical dirs and/or git refs)
	 * @return the directory the agent should operate in
	 * @throws IOException if the workspace cannot be created
	 */
	Path provision(ResolvedItem resolved) throws IOException;

	/**
	 * Release a workspace after the item completes (and after any preservation copy has
	 * been taken). Ephemeral workspaces are deleted; reused checkouts may be left in
	 * place for the next item.
	 * @param workspace the workspace returned by {@link #provision(ResolvedItem)}
	 */
	void release(Path workspace);

	/**
	 * Tear down any provisioner-wide resources (e.g. a shared checkout cache) at the end
	 * of the experiment. Default is a no-op. Overrides {@link AutoCloseable#close()} but
	 * does not throw checked exceptions.
	 */
	@Override
	default void close() {
	}

}
