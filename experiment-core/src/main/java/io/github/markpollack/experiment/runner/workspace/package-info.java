/**
 * Workspace provisioning seam for the experiment runner. A
 * {@link io.github.markpollack.experiment.runner.workspace.WorkspaceProvisioner}
 * materializes the directory an agent operates in for each item — either from a physical
 * fixture
 * ({@link io.github.markpollack.experiment.runner.workspace.DefaultWorkspaceProvisioner})
 * or by checking out a git {@code SourceRef}
 * ({@link io.github.markpollack.experiment.runner.workspace.GitWorkspaceProvisioner}).
 */
@NullMarked
package io.github.markpollack.experiment.runner.workspace;

import org.jspecify.annotations.NullMarked;
