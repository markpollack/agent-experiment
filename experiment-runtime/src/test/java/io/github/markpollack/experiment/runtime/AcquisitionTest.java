package io.github.markpollack.experiment.runtime;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import io.github.markpollack.experiment.runtime.AgentRecords.*;
import io.github.markpollack.workflow.flows.StepContext;

class AcquisitionTest {

	@TempDir
	Path temp;

	static FileRef manifest(Path dir, Attempt attempt, boolean complete, int exit) throws Exception {
		Files.createDirectory(dir);
		var value = Map.of("kind", "agent-journal/acquired-execution", "version", "0.1", "profile_id",
				"test-fixture-not-live", "acquisition_uid", attempt.invocationId(), "complete", complete, "run",
				Map.of("run_uid", attempt.invocationId(), "launch_settings",
						Map.of("artifact_provenance", AcquisitionAssembly.provenance(attempt))),
				"process", Map.of("status", "exited", "exit_code", exit));
		Path file = dir.resolve("acquisition.json");
		RuntimeJson.mapper().writeValue(file.toFile(), value);
		return Evidence.reference(file, "acquisition-manifest");
	}

	static Invocation invocation(Attempt a, FileRef manifest) {
		return new Invocation(a, "FAILED", "exited", List.of(7), "COMPLETE", List.of(manifest), List.of(),
				"NOT_ATTEMPTED", List.of(),
				List.of(Measurement.unknown("cost", "USD", "not reported", a.invocationId())), "failed response", "",
				1000, 5);
	}

	private AcquisitionAssembly assembly(AtomicInteger calls) {
		return new AcquisitionAssembly((manifest, output) -> {
			calls.incrementAndGet();
			Files.createDirectory(output);
			Files.writeString(output.resolve("events.jsonl"), "{\"test\":true}\n");
			Files.writeString(output.resolve("child.jsonl"), "{\"testChild\":true}\n");
			return "COMPLETED";
		}, "test-double-not-live");
	}

	@Test
	void failedExecutionWithCompleteCaptureAssemblesOnceAndStoresSeparateHashes() throws Exception {
		var a = RuntimeStoreTest.attempt(RuntimeStoreTest.identity("run", 0));
		var receipt = manifest(temp.resolve("acquired"), a, true, 7);
		var calls = new AtomicInteger();
		var assembler = assembly(calls);
		var assembled = assembler.assemble(invocation(a, receipt), temp.resolve("canonical"));
		assertThat(assembled.responseState()).isEqualTo("FAILED");
		assertThat(assembled.exitCodes()).containsExactly(7);
		assertThat(assembled.assemblyState()).isEqualTo("ASSEMBLED");
		assertThat(calls).hasValue(1);
		assertThat(assembled.canonical().getFirst().events().sha256()).isNotEqualTo(receipt.sha256());
		assertThat(assembled.canonical().getFirst().verificationState()).isEqualTo("NOT_SOURCE_VERIFIED");
		var row = new Row(a.row(), List.of(assembled), "NOT_STARTED", new Domain(List.of(), List.of()), List.of(),
				"NOT_ASSESSED", "", "RETAINED", List.of());
		var store = new FileSystemResultStore(temp.resolve("results"));
		store.save(RuntimeStoreTest.result("run", List.of(row)));
		assertThat(new FileSystemResultStore(temp.resolve("results")).load("run").orElseThrow().items())
			.containsExactly(row);
		Evidence.verify(row);
		assertThatThrownBy(() -> assembler.assemble(assembled, temp.resolve("another")))
			.hasMessageContaining("already selected");
		var duplicate = assembler.assemble(invocation(a, receipt), temp.resolve("canonical"));
		assertThat(duplicate.assemblyState()).isEqualTo("REFUSED");
		assertThat(calls).hasValue(1);
	}

	@Test
	void validHashesCannotHideWrongRunReplicateVariantOrPhysicalAttempt() throws Exception {
		var a = RuntimeStoreTest.attempt(RuntimeStoreTest.identity("run", 0));
		var receipt = manifest(temp.resolve("acquired"), a, true, 0);
		List<Attempt> wrong = List.of(
				new Attempt(Identity.of("other", "item", "simple", 0), a.invocationId(), "wf", "logical", "physical",
						1),
				new Attempt(Identity.of("run", "item", "simple", 1), a.invocationId(), "wf", "logical", "physical", 1),
				new Attempt(Identity.of("run", "item", "other", 0), a.invocationId(), "wf", "logical", "physical", 1),
				new Attempt(a.row(), a.invocationId(), "wf", "logical", "wrong", 1));
		for (Attempt swapped : wrong)
			assertThatThrownBy(() -> AcquisitionAssembly.validateManifest(receipt, swapped, true))
				.hasMessageContaining("association mismatch");
		var calls = new AtomicInteger();
		var refused = assembly(calls).assemble(invocation(wrong.getFirst(), receipt), temp.resolve("output"));
		assertThat(refused.assemblyState()).isEqualTo("REFUSED");
		assertThat(refused.acquisitions()).containsExactly(receipt);
		assertThat(calls).hasValue(0);
	}

	@Test
	void tamperingVersionAndUnsealedInputsRefuseBeforeAssembly() throws Exception {
		var a = RuntimeStoreTest.attempt(RuntimeStoreTest.identity("run", 0));
		var receipt = manifest(temp.resolve("acquired"), a, false, 0);
		assertThatThrownBy(() -> AcquisitionAssembly.validateManifest(receipt, a, true))
			.hasMessageContaining("incomplete");
		Files.writeString(Path.of(receipt.path()), "{}");
		assertThatThrownBy(() -> AcquisitionAssembly.validateManifest(receipt, a, false))
			.hasMessageContaining("Hash mismatch");
		var newer = RuntimeJson.mapper()
			.createObjectNode()
			.put("kind", "agent-journal/acquired-execution")
			.put("version", "99");
		RuntimeJson.mapper().writeValue(Path.of(receipt.path()).toFile(), newer);
		var newRef = Evidence.reference(Path.of(receipt.path()), "acquisition-manifest");
		assertThatThrownBy(() -> AcquisitionAssembly.validateManifest(newRef, a, false))
			.hasMessageContaining("Unsupported acquisition");
	}

	@Test
	void serviceRetainsSealedLateDeliveryWithoutAssembly() throws Exception {
		var calls = new AtomicInteger();
		var terminated = new AtomicInteger();
		var id = RuntimeStoreTest.identity("run", 0);
		var service = new ClaudeInvocationService(
				(request, attempt, dir, budget) -> new ClaudeInvocationService.ProviderCall() {
					final CountDownLatch release = new CountDownLatch(1);

					public ClaudeInvocationService.Delivery invoke() throws Exception {
						release.await();
						return new ClaudeInvocationService.Delivery("SUCCEEDED", "late",
								List.of(manifest(dir, attempt, true, 0)), true, List.of(), List.of(), "");
					}

					public void close() {
						terminated.incrementAndGet();
						release.countDown();
					}
				}, Duration.ofSeconds(1), assembly(calls));
		var request = new ClaudeInvocationService.Request(new PreparedItem(id, temp.toString(), List.of(), List.of()),
				"prompt", "model", 30, temp.resolve("attempt").toString(), true);
		var outcome = service.invoke(request,
				new StepContext("wf", "logical", "physical", 1, Instant.now().plusSeconds(10)));
		assertThat(outcome.invocation().responseState()).isEqualTo("TIMEOUT");
		assertThat(outcome.invocation().captureState()).isEqualTo("COMPLETE");
		assertThat(outcome.invocation().assemblyState()).isEqualTo("NOT_ATTEMPTED");
		assertThat(calls).hasValue(0);
		assertThat(terminated.get()).isPositive();
		assertThat(temp.resolve("attempt/delivery.json")).exists();
		assertThat(temp.resolve("attempt/outcome.json")).exists();
	}

	@Test
	void serviceQuarantinesUncooperativeProviderAndRetainsLaterEvidence() throws Exception {
		var calls = new AtomicInteger();
		var release = new CountDownLatch(1);
		var wrote = new CountDownLatch(1);
		var id = RuntimeStoreTest.identity("run", 0);
		var service = new ClaudeInvocationService(
				(request, attempt, dir, budget) -> new ClaudeInvocationService.ProviderCall() {
					public ClaudeInvocationService.Delivery invoke() throws Exception {
						release.await();
						var ref = manifest(dir, attempt, true, 0);
						wrote.countDown();
						return new ClaudeInvocationService.Delivery("FAILED", "late", List.of(ref), true, List.of(),
								List.of(), "");
					}

					public void close() {
					}
				}, Duration.ofMillis(20), assembly(calls));
		try {
			assertThatThrownBy(() -> service.invoke(
					new ClaudeInvocationService.Request(new PreparedItem(id, temp.toString(), List.of(), List.of()),
							"p", "m", 30, temp.resolve("late").toString(), true),
					new StepContext("wf", "logical", "physical", 1, Instant.now().plusSeconds(5))))
				.isInstanceOf(ClaudeInvocationService.QuarantinedInvocationException.class);
			var outcome = ClaudeInvocationService.readOutcome(temp.resolve("late"));
			assertThat(outcome.workspaceState()).isEqualTo("QUARANTINED");
			assertThat(outcome.invocation().responseState()).isEqualTo("TIMEOUT");
			assertThat(calls).hasValue(0);
		}
		finally {
			release.countDown();
		}
		assertThat(wrote.await(2, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void providerAdmissionFailureIsRetainedAndCloseFailureQuarantines() throws Exception {
		var id = RuntimeStoreTest.identity("run", 0);
		var prepared = new PreparedItem(id, temp.toString(), List.of(), List.of());
		var context = new StepContext("wf", "logical", "physical", 1, Instant.now().plusSeconds(10));
		var calls = new AtomicInteger();
		var refused = new ClaudeInvocationService((request, attempt, directory, budget) -> {
			throw new IllegalStateException("invalid options");
		}, Duration.ofMillis(100), assembly(calls));
		var outcome = refused.invoke(new ClaudeInvocationService.Request(prepared, "p", "m", 1000,
				temp.resolve("refused").toString(), false), context);
		assertThat(outcome.invocation().responseState()).isEqualTo("THREW");
		assertThat(ClaudeInvocationService.readOutcome(temp.resolve("refused"))).isEqualTo(outcome);
		var brokenClose = new ClaudeInvocationService(
				(request, attempt, directory, budget) -> new ClaudeInvocationService.ProviderCall() {
					public ClaudeInvocationService.Delivery invoke() throws Exception {
						return new ClaudeInvocationService.Delivery("FAILED", "provider failed",
								List.of(manifest(directory, attempt, true, 7)), true, List.of(), List.of(), "");
					}

					public void close() {
						throw new IllegalStateException("termination unavailable");
					}
				}, Duration.ofMillis(100), assembly(calls));
		assertThatThrownBy(() -> brokenClose.invoke(new ClaudeInvocationService.Request(prepared, "p", "m", 1000,
				temp.resolve("close-failure").toString(), false), context))
			.isInstanceOf(ClaudeInvocationService.QuarantinedInvocationException.class);
		var retained = ClaudeInvocationService.readOutcome(temp.resolve("close-failure"));
		assertThat(retained.invocation().acquisitions()).hasSize(1);
		assertThat(retained.invocation().responseState()).isEqualTo("THREW");
		assertThat(retained.workspaceState()).isEqualTo("QUARANTINED");
		assertThat(calls).hasValue(0);
	}

}
