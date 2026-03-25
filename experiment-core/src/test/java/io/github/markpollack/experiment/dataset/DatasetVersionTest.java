package io.github.markpollack.experiment.dataset;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatasetVersionTest {

	@Test
	void createsValidVersion() {
		DatasetVersion version = new DatasetVersion("1.2.0", "a1b2c3d4e5f6", false, 150);

		assertThat(version.semanticVersion()).isEqualTo("1.2.0");
		assertThat(version.gitCommit()).isEqualTo("a1b2c3d4e5f6");
		assertThat(version.dirty()).isFalse();
		assertThat(version.activeItemCount()).isEqualTo(150);
	}

	@Test
	void createsVersionWithNullGitCommit() {
		DatasetVersion version = new DatasetVersion("1.0.0", null, false, 10);

		assertThat(version.gitCommit()).isNull();
		assertThat(version.dirty()).isFalse();
	}

	@Test
	void createsVersionWithDirtyFlag() {
		DatasetVersion version = new DatasetVersion("1.0.0", "abc123", true, 5);

		assertThat(version.dirty()).isTrue();
		assertThat(version.gitCommit()).isEqualTo("abc123");
	}

	@Test
	void zeroItemCountIsValid() {
		DatasetVersion version = new DatasetVersion("0.0.1", null, false, 0);

		assertThat(version.activeItemCount()).isZero();
	}

	@Test
	void negativeItemCountThrows() {
		assertThatThrownBy(() -> new DatasetVersion("1.0.0", null, false, -1))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("activeItemCount");
	}

	@Test
	void nullSemanticVersionThrows() {
		assertThatThrownBy(() -> new DatasetVersion(null, null, false, 0)).isInstanceOf(NullPointerException.class)
			.hasMessageContaining("semanticVersion");
	}

}
