package io.github.markpollack.experiment.scoring;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.github.markpollack.experiment.agent.InvocationResult;
import io.github.markpollack.experiment.agent.TerminalStatus;
import io.github.markpollack.experiment.dataset.DatasetItem;
import io.github.markpollack.experiment.runner.ExperimentConfig;
import org.junit.jupiter.api.Test;
import io.github.markpollack.judge.context.ExecutionStatus;
import io.github.markpollack.judge.context.JudgmentContext;

import static org.assertj.core.api.Assertions.assertThat;

class JudgmentContextFactoryTest {

	private static final Path WORKSPACE = Path.of("/tmp/workspace");

	private static final Path REFERENCE_DIR = Path.of("/tmp/reference");

	private static DatasetItem testItem() {
		return new DatasetItem("TEST-001", "test-item", "Upgrade Spring Boot to 3.x", "spring-boot-3-upgrade", "A",
				false, List.of(), List.of(), "active", Path.of("/dataset/items/TEST-001"), null, null);
	}

	private static InvocationResult testInvocation() {
		return InvocationResult.completed(List.of(), 1000, 500, 200, 0.05, 3000, "session-1", Map.of());
	}

	// ==================== Existing 4-param overload ====================

	@Test
	void createsValidContextFromDatasetItemAndInvocationResult() {
		JudgmentContext ctx = JudgmentContextFactory.create(testItem(), WORKSPACE, testInvocation(), REFERENCE_DIR);

		assertThat(ctx.goal()).isEqualTo("Upgrade Spring Boot to 3.x");
		assertThat(ctx.workspace()).isEqualTo(WORKSPACE);
		assertThat(ctx.executionTime()).isEqualTo(Duration.ofMillis(3000));
		assertThat(ctx.status()).isEqualTo(ExecutionStatus.SUCCESS);
	}

	@Test
	void mapsCompletedToSuccess() {
		assertThat(JudgmentContextFactory.mapStatus(TerminalStatus.COMPLETED)).isEqualTo(ExecutionStatus.SUCCESS);
	}

	@Test
	void mapsTimeoutToTimeout() {
		assertThat(JudgmentContextFactory.mapStatus(TerminalStatus.TIMEOUT)).isEqualTo(ExecutionStatus.TIMEOUT);
	}

	@Test
	void mapsErrorToFailed() {
		assertThat(JudgmentContextFactory.mapStatus(TerminalStatus.ERROR)).isEqualTo(ExecutionStatus.FAILED);
	}

	@Test
	void includesExpectedDirInMetadata() {
		InvocationResult invocation = InvocationResult.completed(List.of(), 0, 0, 0, 0.0, 1000, null, Map.of());

		JudgmentContext ctx = JudgmentContextFactory.create(testItem(), WORKSPACE, invocation, REFERENCE_DIR);

		assertThat(ctx.metadata()).containsEntry("expectedDir", REFERENCE_DIR);
	}

	@Test
	void createsContextWithNullReferenceDir() {
		InvocationResult invocation = InvocationResult.completed(List.of(), 100, 200, 50, 0.01, 1500, null, Map.of());

		JudgmentContext ctx = JudgmentContextFactory.create(testItem(), WORKSPACE, invocation, null);

		assertThat(ctx.goal()).isEqualTo("Upgrade Spring Boot to 3.x");
		assertThat(ctx.workspace()).isEqualTo(WORKSPACE);
		assertThat(ctx.executionTime()).isEqualTo(Duration.ofMillis(1500));
		assertThat(ctx.status()).isEqualTo(ExecutionStatus.SUCCESS);
		assertThat(ctx.metadata()).doesNotContainKey("expectedDir");
	}

	// ==================== beforeDir metadata ====================

	@Test
	void includesBeforeDirInMetadata() {
		Path beforeDir = Path.of("/tmp/before");

		JudgmentContext ctx = JudgmentContextFactory.create(testItem(), WORKSPACE, testInvocation(), null, beforeDir,
				null, null);

		assertThat(ctx.metadata()).containsEntry("beforeDir", beforeDir);
	}

	@Test
	void omitsBeforeDirWhenNull() {
		JudgmentContext ctx = JudgmentContextFactory.create(testItem(), WORKSPACE, testInvocation(), null, null, null,
				null);

		assertThat(ctx.metadata()).doesNotContainKey("beforeDir");
	}

	// ==================== Enriched 7-param overload ====================

	@Test
	void fullEnrichmentPopulatesAllMetadataKeys() {
		String planRoadmap = "# Roadmap\n## Step 1";
		ExperimentConfig config = ExperimentConfig.builder()
			.experimentName("test")
			.datasetDir(Path.of("/dataset"))
			.model("sonnet")
			.promptTemplate("{{task}}")
			.perItemTimeout(Duration.ofMinutes(5))
			.metadata(Map.of("targetBootVersion", "3.0.0", "targetJavaVersion", "17", "targetClassVersion", "61"))
			.build();

		JudgmentContext ctx = JudgmentContextFactory.create(testItem(), WORKSPACE, testInvocation(), REFERENCE_DIR,
				null, planRoadmap, config);

		assertThat(ctx.metadata()).containsEntry("expectedDir", REFERENCE_DIR);
		assertThat(ctx.metadata()).containsEntry("plan", planRoadmap);
		assertThat(ctx.metadata()).containsEntry("targetBootVersion", "3.0.0");
		assertThat(ctx.metadata()).containsEntry("targetJavaVersion", "17");
		assertThat(ctx.metadata()).containsEntry("targetClassVersion", 61);
	}

	@Test
	void partialEnrichmentOnlyPlan() {
		String planRoadmap = "# Roadmap";

		JudgmentContext ctx = JudgmentContextFactory.create(testItem(), WORKSPACE, testInvocation(), null, null,
				planRoadmap, null);

		assertThat(ctx.metadata()).containsEntry("plan", planRoadmap);
		assertThat(ctx.metadata()).doesNotContainKey("targetBootVersion");
	}

	@Test
	void partialEnrichmentOnlyConfig() {
		ExperimentConfig config = ExperimentConfig.builder()
			.experimentName("test")
			.datasetDir(Path.of("/dataset"))
			.model("sonnet")
			.promptTemplate("{{task}}")
			.perItemTimeout(Duration.ofMinutes(5))
			.metadata(Map.of("targetBootVersion", "3.0.0"))
			.build();

		JudgmentContext ctx = JudgmentContextFactory.create(testItem(), WORKSPACE, testInvocation(), null, null, null,
				config);

		assertThat(ctx.metadata()).containsEntry("targetBootVersion", "3.0.0");
		assertThat(ctx.metadata()).doesNotContainKey("targetJavaVersion");
		assertThat(ctx.metadata()).doesNotContainKey("targetClassVersion");
		assertThat(ctx.metadata()).doesNotContainKey("plan");
	}

	@Test
	void noEnrichmentBackwardCompatible() {
		JudgmentContext ctx = JudgmentContextFactory.create(testItem(), WORKSPACE, testInvocation(), null, null, null,
				null);

		assertThat(ctx.goal()).isEqualTo("Upgrade Spring Boot to 3.x");
		assertThat(ctx.workspace()).isEqualTo(WORKSPACE);
		assertThat(ctx.status()).isEqualTo(ExecutionStatus.SUCCESS);
		assertThat(ctx.metadata()).isEmpty();
	}

	@Test
	void configWithNoTargetVersionsAddsNoMetadata() {
		ExperimentConfig config = ExperimentConfig.builder()
			.experimentName("test")
			.datasetDir(Path.of("/dataset"))
			.model("sonnet")
			.promptTemplate("{{task}}")
			.perItemTimeout(Duration.ofMinutes(5))
			.metadata(Map.of("someOtherKey", "value"))
			.build();

		JudgmentContext ctx = JudgmentContextFactory.create(testItem(), WORKSPACE, testInvocation(), null, null, null,
				config);

		assertThat(ctx.metadata()).doesNotContainKey("targetBootVersion");
		assertThat(ctx.metadata()).doesNotContainKey("targetJavaVersion");
		assertThat(ctx.metadata()).doesNotContainKey("targetClassVersion");
	}

	@Test
	void targetClassVersionParsedAsInteger() {
		ExperimentConfig config = ExperimentConfig.builder()
			.experimentName("test")
			.datasetDir(Path.of("/dataset"))
			.model("sonnet")
			.promptTemplate("{{task}}")
			.perItemTimeout(Duration.ofMinutes(5))
			.metadata(Map.of("targetClassVersion", "61"))
			.build();

		JudgmentContext ctx = JudgmentContextFactory.create(testItem(), WORKSPACE, testInvocation(), null, null, null,
				config);

		Object classVersion = ctx.metadata().get("targetClassVersion");
		assertThat(classVersion).isInstanceOf(Integer.class);
		assertThat(classVersion).isEqualTo(61);
	}

}
