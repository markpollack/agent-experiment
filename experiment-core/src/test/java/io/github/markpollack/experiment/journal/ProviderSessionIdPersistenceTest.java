package io.github.markpollack.experiment.journal;

import java.nio.file.*;
import io.github.markpollack.experiment.runtime.FileSystemResultStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ProviderSessionIdPersistenceTest {

	@Test
	void providerSessionAndWorkflowRunStayDistinctAfterReopen(@TempDir Path root) throws Exception {
		var expected = CanonicalFixtures.result("experiment-run", "provider-session");
		new FileSystemResultStore(root).save(expected);
		var stored = new FileSystemResultStore(root).load("experiment-run").orElseThrow();
		var invocation = stored.items().getFirst().invocations().getFirst();
		assertThat(invocation.providerSessions()).containsExactly("provider-session");
		assertThat(invocation.attempt().workflowRunId()).isEqualTo("wf-experiment-run");
		assertThat(invocation.attempt().row().runId()).isEqualTo("experiment-run");
		assertThat(invocation.usage().getFirst().values()).isEmpty();
		assertThat(stored).isEqualTo(expected);
	}

}
