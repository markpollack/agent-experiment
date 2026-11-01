package io.github.markpollack.experiment.journal;

import java.nio.file.*;
import java.util.concurrent.*;
import io.github.markpollack.experiment.runtime.FileSystemResultStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/**
 * Replaces the retired global Journal configuration race with canonical isolated writers.
 */
class ExperimentJournalConcurrencyTest {

	@Test
	void canonicalWritersPreserveIndependentRunAndProviderIdentities(@TempDir Path root) throws Exception {
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var one = executor.submit(() -> {
				new FileSystemResultStore(root).save(CanonicalFixtures.result("one", "provider-one"));
				return 1;
			});
			var two = executor.submit(() -> {
				new FileSystemResultStore(root).save(CanonicalFixtures.result("two", "provider-two"));
				return 2;
			});
			assertThat(one.get(10, TimeUnit.SECONDS)).isEqualTo(1);
			assertThat(two.get(10, TimeUnit.SECONDS)).isEqualTo(2);
		}
		var reader = new FileSystemResultStore(root);
		assertThat(reader.list()).hasSize(2);
		assertThat(reader.load("one")).contains(CanonicalFixtures.result("one", "provider-one"));
		assertThat(reader.load("two")).contains(CanonicalFixtures.result("two", "provider-two"));
	}

}
