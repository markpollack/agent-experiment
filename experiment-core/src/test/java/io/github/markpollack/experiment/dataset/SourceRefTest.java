package io.github.markpollack.experiment.dataset;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceRefTest {

	@Test
	void createsValidSourceRef() {
		SourceRef ref = new SourceRef(Path.of("/home/user/projects/openws"), "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2",
				"services/core");

		assertThat(ref.repoPath()).isEqualTo(Path.of("/home/user/projects/openws"));
		assertThat(ref.commitHash()).isEqualTo("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2");
		assertThat(ref.subDirectory()).isEqualTo("services/core");
	}

	@Test
	void factoryWithoutSubDirectory() {
		SourceRef ref = SourceRef.of(Path.of("/repo"), "abc123");

		assertThat(ref.repoPath()).isEqualTo(Path.of("/repo"));
		assertThat(ref.commitHash()).isEqualTo("abc123");
		assertThat(ref.subDirectory()).isNull();
	}

	@Test
	void factoryWithSubDirectory() {
		SourceRef ref = SourceRef.of(Path.of("/repo"), "abc123", "sub/dir");

		assertThat(ref.subDirectory()).isEqualTo("sub/dir");
	}

	@Test
	void nullRepoPathThrows() {
		assertThatThrownBy(() -> SourceRef.of(null, "abc123")).isInstanceOf(NullPointerException.class)
			.hasMessageContaining("repoPath");
	}

	@Test
	void nullCommitHashThrows() {
		assertThatThrownBy(() -> SourceRef.of(Path.of("/repo"), null)).isInstanceOf(NullPointerException.class)
			.hasMessageContaining("commitHash");
	}

	@Test
	void emptyCommitHashThrows() {
		assertThatThrownBy(() -> SourceRef.of(Path.of("/repo"), "")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("must not be empty");
	}

	@Test
	void nonHexCommitHashThrows() {
		assertThatThrownBy(() -> SourceRef.of(Path.of("/repo"), "xyz123")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("hex characters");
	}

	@Test
	void upperCaseHexCommitHashThrows() {
		assertThatThrownBy(() -> SourceRef.of(Path.of("/repo"), "ABC123")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("hex characters");
	}

}
