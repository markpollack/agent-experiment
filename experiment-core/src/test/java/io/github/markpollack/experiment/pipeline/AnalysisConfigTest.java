package io.github.markpollack.experiment.pipeline;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisConfigTest {

	@Test
	void pomOnlyFactory() {
		AnalysisConfig config = AnalysisConfig.pomOnly();

		assertThat(config.strategy()).isEqualTo(AnalysisStrategy.POM_ONLY);
		assertThat(config.targetBootVersion()).isNull();
		assertThat(config.targetJavaVersion()).isNull();
	}

	@Test
	void pomOnlyWithTargetVersions() {
		AnalysisConfig config = AnalysisConfig.pomOnly("3.0.0", "17");

		assertThat(config.strategy()).isEqualTo(AnalysisStrategy.POM_ONLY);
		assertThat(config.targetBootVersion()).isEqualTo("3.0.0");
		assertThat(config.targetJavaVersion()).isEqualTo("17");
	}

	@Test
	void nullStrategyThrows() {
		assertThatThrownBy(() -> new AnalysisConfig(null, null, null)).isInstanceOf(NullPointerException.class)
			.hasMessageContaining("strategy");
	}

}
