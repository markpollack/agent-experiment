package io.github.markpollack.experiment.runner.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.github.markpollack.experiment.dataset.DatasetItem;
import io.github.markpollack.experiment.dataset.ResolvedItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultWorkspaceProvisionerTest {

	private final DefaultWorkspaceProvisioner provisioner = new DefaultWorkspaceProvisioner();

	@Test
	void copiesBeforeDirIntoFreshWorkspace(@TempDir Path tempDir) throws IOException {
		Path beforeDir = Files.createDirectories(tempDir.resolve("before"));
		Files.writeString(beforeDir.resolve("a.txt"), "alpha");
		Files.createDirectories(beforeDir.resolve("sub"));
		Files.writeString(beforeDir.resolve("sub/b.txt"), "beta");

		Path workspace = provisioner.provision(resolved(beforeDir));

		assertThat(workspace).isNotEqualTo(beforeDir);
		assertThat(workspace.resolve("a.txt")).hasContent("alpha");
		assertThat(workspace.resolve("sub/b.txt")).hasContent("beta");

		provisioner.release(workspace);
	}

	@Test
	void yieldsEmptyWorkspaceWhenNoBeforeDir() throws IOException {
		Path workspace = provisioner.provision(resolved(null));

		assertThat(workspace).isEmptyDirectory();

		provisioner.release(workspace);
	}

	@Test
	void releaseDeletesWorkspace(@TempDir Path tempDir) throws IOException {
		Path beforeDir = Files.createDirectories(tempDir.resolve("before"));
		Files.writeString(beforeDir.resolve("a.txt"), "alpha");

		Path workspace = provisioner.provision(resolved(beforeDir));
		assertThat(workspace).exists();

		provisioner.release(workspace);

		assertThat(workspace).doesNotExist();
		assertThat(beforeDir).exists(); // source fixture untouched
	}

	private static ResolvedItem resolved(Path beforeDir) {
		DatasetItem item = new DatasetItem("ITEM-001", "task", "Do the thing", "task", "A", false, List.of(), List.of(),
				"active", Path.of("items/ITEM-001"), null, null);
		return new ResolvedItem(item, beforeDir, null, Path.of("item.json"), null, null);
	}

}
