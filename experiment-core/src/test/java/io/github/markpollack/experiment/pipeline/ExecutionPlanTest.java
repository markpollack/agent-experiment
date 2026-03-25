package io.github.markpollack.experiment.pipeline;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionPlanTest {

	@Test
	void constructionWithAllFields() {
		ExecutionPlan plan = new ExecutionPlan("# Roadmap\n\n## Step 1\n- RUN javax-to-jakarta",
				List.of("javax-to-jakarta", "pom-upgrader"), List.of(), 0.05, 1000, 2000, 500, 15000, "session-123");

		assertThat(plan.roadmapMarkdown()).contains("javax-to-jakarta");
		assertThat(plan.toolRecommendations()).containsExactly("javax-to-jakarta", "pom-upgrader");
		assertThat(plan.planningCostUsd()).isEqualTo(0.05);
		assertThat(plan.planningInputTokens()).isEqualTo(1000);
		assertThat(plan.planningOutputTokens()).isEqualTo(2000);
		assertThat(plan.planningThinkingTokens()).isEqualTo(500);
		assertThat(plan.planningDurationMs()).isEqualTo(15000);
		assertThat(plan.planningSessionId()).isEqualTo("session-123");
	}

	@Test
	void planningTotalTokens() {
		ExecutionPlan plan = new ExecutionPlan("roadmap", List.of(), List.of(), 0.0, 100, 200, 50, 1000, null);

		assertThat(plan.planningTotalTokens()).isEqualTo(350);
	}

	@Test
	void nullSessionIdAllowed() {
		ExecutionPlan plan = new ExecutionPlan("roadmap", List.of(), List.of(), 0.0, 0, 0, 0, 0, null);

		assertThat(plan.planningSessionId()).isNull();
	}

	@Test
	void nullRoadmapThrows() {
		assertThatThrownBy(() -> new ExecutionPlan(null, List.of(), List.of(), 0.0, 0, 0, 0, 0, null))
			.isInstanceOf(NullPointerException.class)
			.hasMessageContaining("roadmapMarkdown");
	}

	@Test
	void toolRecommendationsAreImmutableCopy() {
		java.util.ArrayList<String> tools = new java.util.ArrayList<>();
		tools.add("javax-to-jakarta");
		ExecutionPlan plan = new ExecutionPlan("roadmap", tools, List.of(), 0.0, 0, 0, 0, 0, null);

		tools.add("pom-upgrader");
		assertThat(plan.toolRecommendations()).hasSize(1);
	}

}
