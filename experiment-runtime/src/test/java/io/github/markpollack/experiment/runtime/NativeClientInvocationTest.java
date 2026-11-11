package io.github.markpollack.experiment.runtime;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.security.MessageDigest;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.agents.client.*;
import io.github.markpollack.agents.client.journal.JournalExecutionRecorder;
import io.github.markpollack.agents.claude.ClaudeAcquisition;
import io.github.markpollack.agents.model.*;
import io.github.markpollack.experiment.runtime.AgentRecords.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Synthetic sources, actual Client/Journal/store; not native inference or qualification.
 */
class NativeClientInvocationTest {

	@TempDir
	Path root;

	private final ObjectMapper json = new ObjectMapper();

	record Fixture(AgentClient client, AsyncClientInvocationService.StartedExecution started,
			ExecutionTermination termination, Path original, Path qualified, Path events) implements AutoCloseable {
		Invocation accept() throws Exception {
			return NativeClientInvocation.accept(started, termination, original, qualified, events, 5000, 1);
		}

		public void close() {
			client.close();
		}
	}

	private Fixture fixture(String name) throws Exception {
		return fixture(name, false);
	}

	/** How the fixture's turn seals its native generation. */
	enum Sealing {

		QUALIFYING, UNKNOWN_PROFILE, CHANGED_AFTER_SEALING

	}

	private Fixture fixture(String name, boolean failed) throws Exception {
		return fixture(name, failed, Sealing.QUALIFYING);
	}

	private Fixture fixture(String name, boolean failed, Sealing sealing) throws Exception {
		return fixture(name, failed, sealing, "2.1.316");
	}

	private Fixture fixture(String name, boolean failed, Sealing sealing, String cliVersion) throws Exception {
		return fixture(name, failed, sealing, cliVersion, false);
	}

	private Fixture fixture(String name, boolean failed, Sealing sealing, String cliVersion, boolean untimed)
			throws Exception {
		Path base = Files.createDirectory(root.resolve(name));
		Path store = base.resolve("journal");
		Path[] original = { null };
		AgentExecutionProvider provider = options -> new AgentExecutionProvider.Connection() {
			public AgentExecutionProvider.Turn prepare(String id) {
				return new AgentExecutionProvider.Turn() {
					public AgentResponse call(AgentTaskRequest request) {
						if (failed)
							throw new IllegalStateException("explicit deterministic failure");
						return new AgentResponse(List
							.of(new AgentGeneration("hello", new AgentGenerationMetadata("SUCCESS", Map.of()))));
					}

					public CaptureAcquisition captureAcquisition() {
						return CaptureAcquisition.COMPLETED;
					}

					public Optional<NativeGeneration> generation(ExecutionDecision decision) {
						try {
							var captured = source(base, decision, cliVersion);
							original[0] = captured.manifest();
							String profile = sealing == Sealing.UNKNOWN_PROFILE ? "claude-code-0.0.0-unknown-route"
									: "claude-code-" + cliVersion + "-sdk-stdio-session-file";
							var generation = NativeGeneration.oneShot(profile, decision, captured.manifest(),
									captured.sha256(), true, "");
							if (sealing == Sealing.CHANGED_AFTER_SEALING)
								Files.writeString(captured.manifest(), "\n", StandardOpenOption.APPEND);
							return Optional.of(generation);
						}
						catch (Exception e) {
							throw new IllegalStateException(e);
						}
					}

					public void stop(boolean interrupt) {
					}
				};
			}

			public void close() {
			}
		};
		var recorder = new JournalExecutionRecorder(store, "native-mapping-fixture");
		var client = AgentClient.builder(provider).executionRecorder(recorder).build();
		var identity = Identity.of("run-" + name, "item", "variant", 0);
		var attempt = new Attempt(identity, "experiment-" + name, "workflow-" + name, "step-" + name,
				"physical-" + name, 1);
		var service = new AsyncClientInvocationService(client);
		var started = untimed ? service.startUntimed(attempt, base, "hello", Optional.of("definition"))
				: service.start(attempt, base, "hello", Duration.ofSeconds(5), Optional.of("definition"));
		if (failed)
			assertThatThrownBy(() -> started.execution().result(Duration.ofSeconds(5)))
				.isInstanceOf(AgentExecutionException.class);
		else
			started.execution().result(Duration.ofSeconds(5));
		var termination = started.execution().awaitTermination(Duration.ofSeconds(5));
		long limit = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		while (!(started.execution().recording().delivery() instanceof TerminalDelivery.Completed)
				&& System.nanoTime() < limit)
			Thread.sleep(5);
		assertThat(started.execution().recording().delivery()).isInstanceOf(TerminalDelivery.Completed.class);
		Path events;
		try (var paths = Files.walk(store.resolve("prepared"))) {
			events = paths.filter(p -> p.getFileName().toString().equals("events.jsonl")).findFirst().orElseThrow();
		}
		return new Fixture(client, started, termination, original[0],
				store.resolve("native-" + started.execution().executionId()), events);
	}

	static Invocation rebind(Invocation i, Attempt a, List<Qualification> q, List<CanonicalRef> c) {
		return new Invocation(a, i.responseState(), i.processState(), i.exitCodes(), i.captureState(), i.acquisitions(),
				c, i.assemblyState(), i.providerSessions(), i.usage(), i.responseText(), i.error(),
				i.providerBudgetMs(), i.elapsedMs(), q);
	}

	static Result result(Invocation i) {
		var jury = io.github.markpollack.judge.jury.SimpleJury.builder()
			.judge("exact-response",
					() -> i.responseText().equals("hello")
							? io.github.markpollack.judge.judgment.Judgment.pass("Exact fixture response")
							: io.github.markpollack.judge.judgment.Judgment.fail("Mismatch"))
			.votingStrategy(new io.github.markpollack.judge.voting.ConsensusStrategy())
			.build();
		var verdict = JudgeRecording.capture(jury.vote());
		var row = new Row(i.attempt().row(), List.of(i), "NOT_STARTED", new Domain(List.of(), List.of()),
				List.of(verdict), "RETURNED", "", "RETAINED", List.of());
		return new Result(AgentRecords.CURRENT_SCHEMA_VERSION, "AGENT_CANONICAL", i.attempt().row().runId(),
				"native-mapping-fixture", 1, List.of(row), List.of());
	}

	@Test
	void untimedNativeInvocationRetainsExplicitZeroThroughTheRealStore() throws Exception {
		try (var f = fixture("untimed", false, Sealing.QUALIFYING, "2.1.319", true)) {
			var invocation = NativeClientInvocation.accept(f.started(), f.termination(), f.original(), f.qualified(),
					f.events(), 0, 1);
			var saved = result(invocation);
			Path directory = root.resolve("untimed-results");
			new FileSystemResultStore(directory).save(saved);
			var read = new FileSystemResultStore(directory).load(saved.experimentId()).orElseThrow();
			var retained = read.items().getFirst().invocations().getFirst();
			assertThat(retained.providerBudgetMs()).isZero();
			assertThat(retained.elapsedMs()).isEqualTo(1);
			assertThat(retained.responseState()).isEqualTo("SUCCEEDED");
			assertThat(retained.attempt()).isEqualTo(f.started().attempt());
			assertThat(retained.usage()).allMatch(m -> m.state().equals("UNKNOWN") && m.values().isEmpty());
			NativeRecordingEvidence.verify(retained);
		}
	}

	@Test
	void nativeArchiveKeepsDistinctIdentitiesOriginalResponseAndStoreRoundTrip() throws Exception {
		try (var f = fixture("valid")) {
			assertThat(outcome(f)).isEqualTo(NativeRecording.Outcome.ATTACHED);
			var i = f.accept();
			assertThat(i.assemblyState()).isEqualTo("ASSEMBLED");
			assertThat(i.qualifications()).singleElement().satisfies(q -> {
				assertThat(q.state()).isEqualTo("QUALIFIED");
				assertThat(q.manifests()).hasSize(1);
				assertThat(q.assemblyExpectations()).hasSize(1);
			});
			assertThat(i.attempt()).isSameAs(f.started().attempt());
			assertThat(i.attempt().invocationId()).isNotEqualTo(f.started().execution().executionId());
			assertThat(i.responseText()).isEqualTo("hello");
			assertThat(i.exitCodes()).isEmpty();
			assertThat(i.usage()).allMatch(x -> x.state().equals("UNKNOWN"));
			assertThat(i.canonical().getFirst().verificationState()).isEqualTo("NOT_SOURCE_VERIFIED");
			var result = result(i);
			var store = new FileSystemResultStore(root.resolve("results"));
			store.save(result);
			var read = store.load(result.experimentId()).orElseThrow();
			Evidence.verify(read.items().getFirst());
			assertThatThrownBy(
					() -> AcquisitionAssembly.validateManifest(i.acquisitions().getFirst(), i.attempt(), true))
				.isInstanceOf(IOException.class)
				.hasMessageContaining("invocation mismatch");
		}
	}

	@Test
	void claudeRecordingUnderTheNeutralTerminalProfileIsAdmitted() throws Exception {
		try (var f = fixture("neutral")) {
			assertThat(Files.readString(f.events())).contains("client-terminal-claude-sdk/1");
			var facts = NativeRecordingEvidence.verify(f.accept());
			assertThat(facts.original().path("profile_id").asText())
				.isEqualTo("claude-code/2.1.316/sdk-stdio+session-file");
		}
	}

	@Test
	void claude2_1_319RecordingUnderTheNeutralTerminalProfileIsAdmitted() throws Exception {
		try (var f = fixture("v2-1-319", false, Sealing.QUALIFYING, "2.1.319")) {
			assertThat(outcome(f)).isEqualTo(NativeRecording.Outcome.ATTACHED);
			assertThat(Files.readString(f.events())).contains("client-terminal-claude-sdk/1")
				.contains("claude-code/2.1.319/sdk-stdio+session-file")
				.doesNotContain("claude-code/2.1.316/");
			var i = f.accept();
			assertThat(i.assemblyState()).isEqualTo("ASSEMBLED");
			assertThat(i.qualifications()).singleElement().satisfies(q -> assertThat(q.state()).isEqualTo("QUALIFIED"));
			var facts = NativeRecordingEvidence.verify(i);
			assertThat(facts.original().path("profile_id").asText())
				.isEqualTo("claude-code/2.1.319/sdk-stdio+session-file");
			assertThat(i.canonical().getFirst().profileId()).isEqualTo("claude-code/2.1.319/sdk-stdio+session-file");
		}
	}

	@Test
	void originalAndQualifiedProfileIdsMustAgree() throws Exception {
		try (var f = fixture("profile-pair")) {
			// A qualified manifest naming another version than its original, still on
			// the route.
			Path qualified = f.qualified().resolve("acquisition.json");
			var manifest = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(qualified.toFile());
			manifest.put("profile_id", "claude-code/2.1.319/sdk-stdio+session-file");
			json.writeValue(qualified.toFile(), manifest);
			assertThatThrownBy(f::accept).isInstanceOf(IOException.class)
				.hasMessageContaining("Protected qualified acquisition substitution");
		}
	}

	@Test
	void profileWithoutJournalQualifierIsUnqualifiedAndRefused() throws Exception {
		try (var f = fixture("unqualified", false, Sealing.UNKNOWN_PROFILE)) {
			assertThat(outcome(f)).isEqualTo(NativeRecording.Outcome.UNQUALIFIED);
			assertThat(f.qualified()).doesNotExist();
			assertThatThrownBy(f::accept).isInstanceOf(IOException.class).hasMessageContaining("UNQUALIFIED");
		}
	}

	@Test
	void manifestChangedAfterSealingIsRejectedAndRefused() throws Exception {
		try (var f = fixture("rejected", false, Sealing.CHANGED_AFTER_SEALING)) {
			assertThat(outcome(f)).isEqualTo(NativeRecording.Outcome.REJECTED);
			assertThat(f.qualified()).doesNotExist();
			assertThatThrownBy(f::accept).isInstanceOf(IOException.class)
				.hasMessageContaining("REJECTED")
				.hasMessageContaining("changed after sealing");
		}
	}

	@Test
	void incompleteQualificationOutputIsRefused() throws Exception {
		try (var f = fixture("incomplete")) {
			assertThat(outcome(f)).isEqualTo(NativeRecording.Outcome.ATTACHED);
			Files.delete(f.qualified().resolve("assembly-expectation.json"));
			assertThatThrownBy(f::accept).isInstanceOf(IOException.class)
				.hasMessageContaining("qualification output is incomplete");
		}
	}

	private static NativeRecording.Outcome outcome(Fixture f) {
		return f.started().execution().recording().nativeRecording().orElseThrow().outcome();
	}

	@Test
	void wrongParentAndIndividuallyValidOtherArchiveAreRefused() throws Exception {
		try (var a = fixture("a"); var b = fixture("b")) {
			var i = a.accept();
			var changed = new Attempt(i.attempt().row(), i.attempt().invocationId(), "wrong",
					i.attempt().workflowInvocationId(), i.attempt().workflowAttemptId(), 1);
			assertThatThrownBy(
					() -> NativeRecordingEvidence.verify(rebind(i, changed, i.qualifications(), i.canonical())))
				.isInstanceOf(IOException.class);
			assertThatThrownBy(() -> NativeClientInvocation.accept(a.started(), a.termination(), a.original(),
					b.qualified(), b.events(), 5000, 1))
				.isInstanceOf(IOException.class);
		}
	}

	@Test
	void wrongAckAndMismatchedSourceRefuseWithoutChangingElectedDecision() throws Exception {
		try (var f = fixture("ack"); var other = fixture("source")) {
			var decision = f.started().decision().orElseThrow();
			Path marker = f.events().resolveSibling("marker.json");
			var data = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(marker.toFile());
			((com.fasterxml.jackson.databind.node.ObjectNode) data.get("ack")).put("bindingId", "wrong-binding");
			json.writeValue(marker.toFile(), data);
			assertThatThrownBy(f::accept).isInstanceOf(IOException.class).hasMessageContaining("bindingId");
			assertThat(f.started().decision().orElseThrow()).isSameAs(decision);
			assertThatThrownBy(() -> NativeClientInvocation.accept(other.started(), other.termination(), f.original(),
					other.qualified(), other.events(), 5000, 1))
				.isInstanceOf(IOException.class);
		}
	}

	@Test
	void fullClosureRejectsMissingChangedAndLinkedSources() throws Exception {
		try (var f = fixture("closure")) {
			var i = f.accept();
			Path source = f.events().getParent().resolve("sources/stdout.bin");
			byte[] bytes = Files.readAllBytes(source);
			Files.writeString(source, "changed");
			assertThatThrownBy(() -> NativeRecordingEvidence.verify(i)).isInstanceOf(IOException.class);
			Files.write(source, bytes);
			Files.delete(source);
			assertThatThrownBy(() -> NativeRecordingEvidence.verify(i)).isInstanceOf(IOException.class);
			Path copy = root.resolve("external");
			Files.write(copy, bytes);
			Files.createSymbolicLink(source, copy);
			assertThatThrownBy(() -> NativeRecordingEvidence.verify(i)).isInstanceOf(IOException.class);
		}
	}

	@Test
	void unknownProfileCannotFallBackToLegacy() throws Exception {
		try (var f = fixture("profile")) {
			String original = Files.readString(f.events());
			Files.writeString(f.events(),
					original.replace("client-terminal-claude-sdk/1", "client-terminal-unknown/1"));
			assertThat(Files.readString(f.events())).isNotEqualTo(original);
			assertThatThrownBy(f::accept).isInstanceOf(IOException.class);
		}
	}

	@Test
	void uncertainTerminationRefusedAndRawDecisionRetained() throws Exception {
		try (var f = fixture("uncertain")) {
			var t = f.termination();
			var unknown = new ExecutionTermination(t.executionId(), t.sessionId(), t.observedAt(), Optional.empty(),
					false, List.of("unknown"), t.providerTurnDispatched(), t.reconnected(), t.sessionState(),
					t.stopReason());
			var decision = f.started().decision().orElseThrow();
			assertThatThrownBy(() -> NativeClientInvocation.accept(f.started(), unknown, f.original(), f.qualified(),
					f.events(), 5000, 1))
				.isInstanceOf(IOException.class);
			assertThat(f.started().decision().orElseThrow()).isSameAs(decision);
		}
	}

	@Test
	void actualFailedDecisionIsRetainedAndForgedSuccessIsRefused() throws Exception {
		try (var f = fixture("failure", true)) {
			var decision = f.started().decision().orElseThrow();
			assertThat(decision.outcome()).isInstanceOf(ExecutionOutcome.Failed.class);
			assertThatThrownBy(f::accept).isInstanceOf(IOException.class).hasMessageContaining("SUCCESS");
			var original = Evidence.reference(f.original(), "acquisition-manifest");
			var q = QualificationEvidence.collect(original, f.qualified(), "QUALIFIED", "fixture", "");
			List<FileRef> retained;
			try (var files = Files.walk(f.events().getParent())) {
				retained = files.filter(Files::isRegularFile).sorted().map(p -> {
					try {
						return Evidence.reference(p,
								p.equals(f.events()) ? "canonical-events" : "canonical-linked-file");
					}
					catch (IOException e) {
						throw new java.io.UncheckedIOException(e);
					}
				}).toList();
			}
			var c = new CanonicalRef(Evidence.reference(f.events(), "canonical-events"), retained,
					"claude-code/2.1.316/sdk-stdio+session-file", "fixture", "COMPLETED", "NOT_SOURCE_VERIFIED");
			var forged = new Invocation(f.started().attempt(), "SUCCEEDED", "sdk_closed_after_result", List.of(),
					"COMPLETE", List.of(original), List.of(c), "ASSEMBLED", List.of("native-1"), List.of(), "hello", "",
					5000, 1, List.of(q));
			assertThatThrownBy(() -> NativeRecordingEvidence.verify(forged)).isInstanceOf(IOException.class)
				.hasMessageContaining("Non-success");
			assertThat(f.started().decision().orElseThrow()).isSameAs(decision);
		}
	}

	@Test
	void hashConsistentChangedOriginalCannotReuseQualification() throws Exception {
		try (var f = fixture("substitution")) {
			var source = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(f.original().toFile());
			((com.fasterxml.jackson.databind.node.ObjectNode) source.get("process")).put("basis",
					"substituted-producer");
			json.writeValue(f.original().toFile(), source);
			assertThatThrownBy(f::accept).isInstanceOf(IOException.class);
		}
	}

	@Test
	void hashConsistentRetainedReferencesCannotHideCorruptSource() throws Exception {
		try (var f = fixture("rehashed")) {
			var i = f.accept();
			var c = i.canonical().getFirst();
			Files.writeString(f.events().getParent().resolve("sources/stdout.bin"), "changed");
			var refs = new ArrayList<FileRef>();
			for (var ref : c.retainedFiles())
				refs.add(Evidence.reference(Path.of(ref.path()), ref.subject()));
			var rehashed = new CanonicalRef(c.events(), refs, c.profileId(), c.writerVersion(), c.assemblyState(),
					c.verificationState());
			assertThatThrownBy(
					() -> NativeRecordingEvidence.verify(rebind(i, i.attempt(), i.qualifications(), List.of(rehashed))))
				.isInstanceOf(IOException.class);
		}
	}

	@Test
	void codexProcessProjectionRequiresOriginalSealedExitAndCleanup() throws Exception {
		var manifest = json.readTree("""
				{"profile_id":"codex-cli/0.166.0/exec-stdio+rollout", "process":
				{"sealed":true,"exit_code":0,"observed_resources_terminated":true}}
				""");
		assertThat(NativeRecordingEvidence.processState(manifest)).isEqualTo("sealed_exit_observed");
		for (String field : List.of("sealed", "exit_code", "observed_resources_terminated")) {
			var missing = manifest.deepCopy();
			((com.fasterxml.jackson.databind.node.ObjectNode) missing.path("process")).remove(field);
			assertThatThrownBy(() -> NativeRecordingEvidence.processState(missing)).isInstanceOf(IOException.class)
				.hasMessageContaining("Codex process settlement is incomplete");
		}
		assertThat(manifest.path("process").has("status")).isFalse();
	}

	private ClaudeAcquisition source(Path fixtureRoot, ExecutionDecision decision, String v) throws Exception {
		Path raw = Files.createDirectory(fixtureRoot.resolve("raw"));
		Files.writeString(raw.resolve("stdin.bin"),
				"{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"hello\"}}\n");
		Files.writeString(raw.resolve("stdout.bin"),
				"""
						{"type":"system","subtype":"init","session_id":"native-1","claude_code_version":"@V@"}
						{"type":"result","subtype":"success","session_id":"native-1","result":"hello","is_error":false,"usage":{"input_tokens":1,"output_tokens":1},"total_cost_usd":0.01}
						"""
					.replace("@V@", v));
		Files.writeString(raw.resolve("stderr.bin"), "");
		Files.writeString(raw.resolve("native.jsonl"),
				"""
						{"type":"user","uuid":"u1","sessionId":"native-1","version":"@V@","message":{"role":"user","content":"hello"}}
						"""
					.replace("@V@", v));
		var manifest = json.readTree(
				"""
						{"kind":"agent-journal/acquired-execution","version":"0.1","profile":"claude-code-@V@-sdk-stdio-session-file",
						"profile_id":"claude-code/@V@/sdk-stdio+session-file","complete":true,"capture_errors":[],
						"run":{"invocation_index":0,"session_uid":"native-1","capture_start":"2026-11-02T00:00:00Z",
						"cli":{"name":"Claude Code","version":"@V@","vendor_name":"Anthropic"},"launch_settings":{"route":"sdk_stdio"}},
						"process":{"argv":["claude","--input-format","stream-json","--output-format","stream-json"],"exit_code":null},
						"turns":[{"start_offsets":{"STDIN":0,"STDOUT":0,"STDERR":0},"end_offsets":{}}],"sources":[]}
						"""
					.replace("@V@", v));
		((com.fasterxml.jackson.databind.node.ObjectNode) manifest).put("acquisition_uid", decision.executionId());
		((com.fasterxml.jackson.databind.node.ObjectNode) manifest.get("run")).put("run_uid", decision.executionId());
		((com.fasterxml.jackson.databind.node.ObjectNode) manifest.get("process")).put("connection_uid",
				decision.connectionId().orElseThrow());
		((com.fasterxml.jackson.databind.node.ObjectNode) manifest.get("process")).put("status",
				"sdk_closed_after_result");
		var turn = (com.fasterxml.jackson.databind.node.ObjectNode) manifest.get("turns").get(0);
		turn.put("invocation_uid", decision.executionId());
		var sources = (com.fasterxml.jackson.databind.node.ArrayNode) manifest.get("sources");
		for (String role : List.of("stdin", "stream", "stderr", "transcript")) {
			String path = switch (role) {
				case "stream" -> "stdout.bin";
				case "transcript" -> "native.jsonl";
				default -> role + ".bin";
			};
			byte[] bytes = Files.readAllBytes(raw.resolve(path));
			var source = sources.addObject()
				.put("artifact", path)
				.put("path", path)
				.put("role", role)
				.put("origin", "observed/" + path)
				.put("state", "acquired")
				.put("acquisition", decision.executionId())
				.put("required", true)
				.put("end", "EOF")
				.put("bytes", bytes.length)
				.put("sha256", hash(bytes));
			if (role.equals("stream") || role.equals("transcript"))
				source.put("profile", role.equals("stream") ? "claude_stream" : "claude_transcript")
					.put("route", role.equals("stream") ? "sdk_stdio" : "native_session_file")
					.put("framing", "lines")
					.put("records", role.equals("stream") ? 2 : 1)
					.put("owner_session_uid", "native-1");
			else
				source.put("archive_only", true);
			if (!role.equals("transcript"))
				((com.fasterxml.jackson.databind.node.ObjectNode) turn.get("end_offsets"))
					.put(role.equals("stream") ? "STDOUT" : role.toUpperCase(Locale.ROOT), bytes.length);
		}
		Path file = raw.resolve("sealed.json");
		json.writeValue(file.toFile(), manifest);
		return new ClaudeAcquisition(file, hash(Files.readAllBytes(file)), true, decision.executionId(), "native-1");
	}

	private static String hash(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

}
