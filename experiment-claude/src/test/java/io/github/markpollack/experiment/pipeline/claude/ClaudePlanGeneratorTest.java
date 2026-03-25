package io.github.markpollack.experiment.pipeline.claude;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.github.markpollack.experiment.pipeline.AnalysisEnvelope;
import io.github.markpollack.experiment.pipeline.AnnotationUsage;
import io.github.markpollack.experiment.pipeline.PlanConfig;
import io.github.markpollack.journal.claude.PhaseCapture;
import io.github.markpollack.journal.claude.ToolUseRecord;
import org.junit.jupiter.api.Test;
import org.springaicommunity.claude.agent.sdk.config.PermissionMode;
import org.springaicommunity.claude.agent.sdk.transport.CLIOptions;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudePlanGeneratorTest {

	private final ClaudePlanGenerator generator = new ClaudePlanGenerator();

	// --- resolveModelId ---

	@Test
	void resolveModelIdShortNames() {
		assertThat(ClaudePlanGenerator.resolveModelId("sonnet")).isEqualTo(CLIOptions.MODEL_SONNET);
		assertThat(ClaudePlanGenerator.resolveModelId("haiku")).isEqualTo(CLIOptions.MODEL_HAIKU);
		assertThat(ClaudePlanGenerator.resolveModelId("opus")).isEqualTo(CLIOptions.MODEL_OPUS);
	}

	@Test
	void resolveModelIdPassthroughFullIds() {
		String fullId = "claude-sonnet-4-5-20250929";
		assertThat(ClaudePlanGenerator.resolveModelId(fullId)).isEqualTo(fullId);
	}

	@Test
	void resolveModelIdCaseInsensitive() {
		assertThat(ClaudePlanGenerator.resolveModelId("SONNET")).isEqualTo(CLIOptions.MODEL_SONNET);
		assertThat(ClaudePlanGenerator.resolveModelId("Haiku")).isEqualTo(CLIOptions.MODEL_HAIKU);
	}

	// --- buildOptions ---

	@Test
	void buildOptionsUsesModelAndTimeout() {
		PlanConfig config = new PlanConfig(null, Map.of(), "3.5.0", "21", "sonnet", Duration.ofMinutes(5));

		CLIOptions options = generator.buildOptions(config);

		assertThat(options.model()).isEqualTo(CLIOptions.MODEL_SONNET);
		assertThat(options.timeout()).isEqualTo(Duration.ofMinutes(5));
		assertThat(options.permissionMode()).isEqualTo(PermissionMode.DANGEROUSLY_SKIP_PERMISSIONS);
	}

	@Test
	void buildOptionsIncludesSystemPrompt() {
		PlanConfig config = new PlanConfig(null, Map.of(), null, null, "haiku", Duration.ofMinutes(3));

		CLIOptions options = generator.buildOptions(config);

		assertThat(options.appendSystemPrompt()).contains("Spring Boot migrations");
	}

	// --- buildSystemPrompt ---

	@Test
	void systemPromptContainsMigrationExpertise() {
		String prompt = generator.buildSystemPrompt();

		assertThat(prompt).contains("Spring Boot migrations");
		assertThat(prompt).contains("Jakarta EE");
		assertThat(prompt).contains("javax to jakarta");
		assertThat(prompt).contains("Maven");
	}

	// --- buildExplorePrompt ---

	@Test
	void explorePromptIncludesProjectAnalysis() {
		AnalysisEnvelope analysis = sampleAnalysis();
		PlanConfig config = new PlanConfig(null, Map.of(), "3.5.0", "21", "sonnet", Duration.ofMinutes(5));

		String prompt = generator.buildExplorePrompt(analysis, config);

		assertThat(prompt).contains("Phase 1");
		assertThat(prompt).contains("test-project");
		assertThat(prompt).contains("2.7.0");
		assertThat(prompt).contains("javax.persistence");
		assertThat(prompt).contains("@Entity");
	}

	@Test
	void explorePromptIncludesKnowledgeStore() {
		PlanConfig config = new PlanConfig(Path.of("/knowledge"), Map.of(), null, null, "sonnet",
				Duration.ofMinutes(5));

		String prompt = generator.buildExplorePrompt(minimalAnalysis(), config);

		assertThat(prompt).contains("Knowledge Store");
		assertThat(prompt).contains("/knowledge");
		assertThat(prompt).contains("index.md");
	}

	@Test
	void explorePromptOmitsKnowledgeStoreWhenNull() {
		PlanConfig config = new PlanConfig(null, Map.of(), null, null, "sonnet", Duration.ofMinutes(5));

		String prompt = generator.buildExplorePrompt(minimalAnalysis(), config);

		assertThat(prompt).doesNotContain("Knowledge Store");
	}

	@Test
	void explorePromptIncludesTargetVersions() {
		PlanConfig config = new PlanConfig(null, Map.of(), "3.5.0", "21", "sonnet", Duration.ofMinutes(5));

		String prompt = generator.buildExplorePrompt(minimalAnalysis(), config);

		assertThat(prompt).contains("Migration Target");
		assertThat(prompt).contains("3.5.0");
		assertThat(prompt).contains("21");
	}

	@Test
	void explorePromptIncludesToolPaths() {
		Map<String, Path> tools = Map.of("javax-to-jakarta", Path.of("/tools/j2j.jar"), "pom-upgrader",
				Path.of("/tools/pom.jar"));
		PlanConfig config = new PlanConfig(null, tools, null, null, "sonnet", Duration.ofMinutes(5));

		String prompt = generator.buildExplorePrompt(minimalAnalysis(), config);

		assertThat(prompt).contains("Available Migration Tools");
		assertThat(prompt).contains("javax-to-jakarta");
		assertThat(prompt).contains("/tools/j2j.jar");
		assertThat(prompt).contains("pom-upgrader");
	}

	@Test
	void explorePromptEndsWithNoRoadmapInstruction() {
		PlanConfig config = new PlanConfig(null, Map.of(), null, null, "sonnet", Duration.ofMinutes(5));

		String prompt = generator.buildExplorePrompt(minimalAnalysis(), config);

		assertThat(prompt).contains("Do NOT generate the roadmap yet");
	}

	@Test
	void explorePromptIncludesDependencies() {
		AnalysisEnvelope analysis = sampleAnalysis();
		PlanConfig config = new PlanConfig(null, Map.of(), null, null, "sonnet", Duration.ofMinutes(5));

		String prompt = generator.buildExplorePrompt(analysis, config);

		assertThat(prompt).contains("spring-boot-starter-web");
		assertThat(prompt).contains("Dependencies (");
	}

	@Test
	void explorePromptIncludesModulesWhenPresent() {
		AnalysisEnvelope analysis = AnalysisEnvelope.builder()
			.projectName("multi-mod")
			.buildTool("maven")
			.modules(List.of("core", "web"))
			.build();
		PlanConfig config = new PlanConfig(null, Map.of(), null, null, "sonnet", Duration.ofMinutes(5));

		String prompt = generator.buildExplorePrompt(analysis, config);

		assertThat(prompt).contains("core, web");
	}

	// --- buildPlanPrompt ---

	@Test
	void planPromptContainsFormatRequirements() {
		PlanConfig config = new PlanConfig(null, Map.of(), null, null, "sonnet", Duration.ofMinutes(5));

		String prompt = generator.buildPlanPrompt(minimalAnalysis(), config);

		assertThat(prompt).contains("Phase 2");
		assertThat(prompt).contains("Migration Roadmap");
		assertThat(prompt).contains("- [ ]");
		assertThat(prompt).contains("RUN");
		assertThat(prompt).contains("VERIFY");
		assertThat(prompt).contains("VERIFY Criteria Requirements");
		assertThat(prompt).contains("unique");
		assertThat(prompt).contains("different aspect");
	}

	@Test
	void planPromptIncludesToolReminder() {
		Map<String, Path> tools = Map.of("javax-to-jakarta", Path.of("/tools/j2j.jar"));
		PlanConfig config = new PlanConfig(null, tools, null, null, "sonnet", Duration.ofMinutes(5));

		String prompt = generator.buildPlanPrompt(minimalAnalysis(), config);

		assertThat(prompt).contains("Available Tools (reminder)");
		assertThat(prompt).contains("javax-to-jakarta");
		assertThat(prompt).contains("Migrates javax.* imports to jakarta.* namespace");
	}

	@Test
	void planPromptDescribesAllTools() {
		Map<String, Path> tools = Map.of("pom-upgrader", Path.of("/t/pom.jar"), "junit-migrator",
				Path.of("/t/junit.jar"), "annotation-migrator", Path.of("/t/ann.jar"), "property-migrator",
				Path.of("/t/prop.jar"));
		PlanConfig config = new PlanConfig(null, tools, null, null, "sonnet", Duration.ofMinutes(5));

		String prompt = generator.buildPlanPrompt(minimalAnalysis(), config);

		assertThat(prompt).contains("Upgrades POM parent version");
		assertThat(prompt).contains("Migrates JUnit 4 tests to JUnit 5");
		assertThat(prompt).contains("Migrates deprecated Spring annotations");
		assertThat(prompt).contains("Migrates application.properties keys");
	}

	@Test
	void planPromptOmitsToolsWhenEmpty() {
		PlanConfig config = new PlanConfig(null, Map.of(), null, null, "sonnet", Duration.ofMinutes(5));

		String prompt = generator.buildPlanPrompt(minimalAnalysis(), config);

		assertThat(prompt).doesNotContain("Available Tools (reminder)");
	}

	// --- extractToolRecommendations ---

	@Test
	void extractToolRecommendationsFindsAllKnownTools() {
		String roadmap = """
				## Stage 1
				- [ ] RUN javax-to-jakarta --apply src/main/java
				- [ ] RUN pom-upgrader --target 3.5.0
				- [ ] RUN thymeleaf-migrator --apply src/main/resources/templates

				## Stage 2
				- [ ] RUN junit-migrator src/test
				- [ ] RUN annotation-migrator src/main
				- [ ] RUN property-migrator src/main/resources
				""";

		List<String> tools = generator.extractToolRecommendations(roadmap);

		assertThat(tools).containsExactly("javax-to-jakarta", "pom-upgrader", "thymeleaf-migrator", "junit-migrator",
				"annotation-migrator", "property-migrator");
	}

	@Test
	void extractToolRecommendationsDeduplicates() {
		String roadmap = """
				- [ ] RUN javax-to-jakarta src/main
				- [ ] RUN javax-to-jakarta src/test
				""";

		List<String> tools = generator.extractToolRecommendations(roadmap);

		assertThat(tools).containsExactly("javax-to-jakarta");
	}

	@Test
	void extractToolRecommendationsReturnsEmptyForNoTools() {
		String roadmap = """
				## Stage 1
				- [ ] Manually update application.properties
				- [ ] VERIFY: ./mvnw clean verify
				""";

		List<String> tools = generator.extractToolRecommendations(roadmap);

		assertThat(tools).isEmpty();
	}

	@Test
	void extractToolRecommendationsPreservesOrder() {
		String roadmap = """
				- [ ] RUN pom-upgrader --target 3.5.0
				- [ ] RUN javax-to-jakarta --apply src/main
				""";

		List<String> tools = generator.extractToolRecommendations(roadmap);

		assertThat(tools).containsExactly("pom-upgrader", "javax-to-jakarta");
	}

	// --- extractKbFilesRead ---

	@Test
	void extractKbFilesReadFindsReadToolCalls() {
		PhaseCapture phase = new PhaseCapture("explore", "prompt", 100, 200, 0, 1000, 0, 0.01, null, 1, false, "output",
				null,
				List.of(new ToolUseRecord("t1", "Read", Map.of("file_path", "/kb/spring/boot-migration.md")),
						new ToolUseRecord("t2", "Read", Map.of("file_path", "/kb/jakarta/servlet-api.md")),
						new ToolUseRecord("t3", "Bash", Map.of("command", "ls"))),
				null);

		List<String> kbFiles = generator.extractKbFilesRead(List.of(phase), Path.of("/kb"));

		assertThat(kbFiles).containsExactly("spring/boot-migration.md", "jakarta/servlet-api.md");
	}

	@Test
	void extractKbFilesReadReturnsEmptyWhenNoKbDir() {
		PhaseCapture phase = new PhaseCapture("explore", "prompt", 100, 200, 0, 1000, 0, 0.01, null, 1, false, "output",
				null, List.of(new ToolUseRecord("t1", "Read", Map.of("file_path", "/kb/file.md"))), null);

		List<String> kbFiles = generator.extractKbFilesRead(List.of(phase), null);

		assertThat(kbFiles).isEmpty();
	}

	@Test
	void extractKbFilesReadFiltersNonKbPaths() {
		PhaseCapture phase = new PhaseCapture("explore", "prompt", 100, 200, 0, 1000, 0, 0.01, null, 1, false, "output",
				null, List.of(new ToolUseRecord("t1", "Read", Map.of("file_path", "/workspace/pom.xml")),
						new ToolUseRecord("t2", "Read", Map.of("file_path", "/kb/index.md"))),
				null);

		List<String> kbFiles = generator.extractKbFilesRead(List.of(phase), Path.of("/kb"));

		assertThat(kbFiles).containsExactly("index.md");
	}

	@Test
	void extractKbFilesReadDeduplicatesAcrossPhases() {
		PhaseCapture phase1 = new PhaseCapture("explore", "prompt", 100, 200, 0, 1000, 0, 0.01, null, 1, false,
				"output", null, List.of(new ToolUseRecord("t1", "Read", Map.of("file_path", "/kb/file.md"))), null);
		PhaseCapture phase2 = new PhaseCapture("plan", "prompt", 100, 200, 0, 1000, 0, 0.01, null, 1, false, "output",
				null, List.of(new ToolUseRecord("t2", "Read", Map.of("file_path", "/kb/file.md"))), null);

		List<String> kbFiles = generator.extractKbFilesRead(List.of(phase1, phase2), Path.of("/kb"));

		assertThat(kbFiles).containsExactly("file.md");
	}

	// --- helpers ---

	private static AnalysisEnvelope minimalAnalysis() {
		return AnalysisEnvelope.builder().projectName("test-project").buildTool("maven").build();
	}

	private static AnalysisEnvelope sampleAnalysis() {
		return AnalysisEnvelope.builder()
			.projectName("test-project")
			.bootVersion("2.7.0")
			.javaVersion("1.8")
			.buildTool("maven")
			.parentCoordinates("org.springframework.boot:spring-boot-starter-parent:2.7.0")
			.dependencies(Map.of("org.springframework.boot:spring-boot-starter-web", "managed", "com.h2database:h2",
					"2.1.214"))
			.importPatterns(Map.of("javax.persistence", List.of("src/main/java/App.java", "src/main/java/Entity.java"),
					"javax.validation", List.of("src/main/java/App.java")))
			.annotations(List.of(new AnnotationUsage("Entity", "src/main/java/MyEntity.java", "MyEntity"),
					new AnnotationUsage("SpringBootApplication", "src/main/java/App.java", "App")))
			.configFiles(List.of("src/main/resources/application.properties"))
			.build();
	}

}
