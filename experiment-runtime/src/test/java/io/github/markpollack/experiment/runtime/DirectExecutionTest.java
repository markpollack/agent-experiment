package io.github.markpollack.experiment.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import io.github.markpollack.experiment.runtime.AgentRecords.*;
import io.github.markpollack.experiment.runtime.ThreeStageConsumerExampleTest.*;
import io.github.markpollack.judge.judgment.Judgment;
import io.github.markpollack.judge.verdict.Verdict;
import io.github.markpollack.workflow.batch.durable.*;
import io.github.markpollack.workflow.flows.Workflows;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel;

/**
 * Direct execution, without Agent Workflow, beside Workflow execution. Providers, Journal
 * assembly and measurements are deterministic fixtures; no model is called.
 */
class DirectExecutionTest {

	private static final String PROMPT = "Add the missing null check to Parser.parse";

	@TempDir
	Path temp;

	/** A Claude-style capture: the manifest records the caller's prompt and model. */
	private static FileRef capture(Path directory, ClaudeInvocationService.Request request, Attempt attempt,
			boolean complete) throws IOException {
		Files.createDirectories(directory);
		var launch = new LinkedHashMap<String, Object>();
		launch.put("model", request.model());
		launch.put("caller_input", Map.of("prompt", request.prompt(), "provenance", "caller-supplied"));
		launch.put("artifact_provenance", AcquisitionAssembly.provenance(attempt));
		var manifest = Map.of("kind", "agent-journal/acquired-execution", "version", "0.1", "profile_id",
				"test-fixture-not-live", "acquisition_uid", attempt.invocationId(), "complete", complete, "run",
				Map.of("run_uid", attempt.invocationId(), "session_uid", "session-" + attempt.invocationId(),
						"launch_settings", launch),
				"process", Map.of("status", "exited", "exit_code", 0));
		Path file = directory.resolve("acquisition.json");
		RuntimeJson.mapper().writeValue(file.toFile(), manifest);
		return Evidence.reference(file, "acquisition-manifest");
	}

	private static AcquisitionAssembly assembly() {
		return new AcquisitionAssembly((manifest, output) -> {
			Files.createDirectory(output);
			Files.writeString(output.resolve("events.jsonl"), "{\"fixture\":true}\n");
			return "COMPLETED";
		}, "test-double-not-live");
	}

	/** Returns a captured success, as a plain Claude call would. */
	private static ClaudeInvocationService claude() {
		return new ClaudeInvocationService(
				(request, attempt, directory, budget) -> new ClaudeInvocationService.ProviderCall() {
					public ClaudeInvocationService.Delivery invoke() throws IOException {
						return new ClaudeInvocationService.Delivery("SUCCEEDED", "Added the null check",
								List.of(capture(directory, request, attempt, true)), true,
								List.of("session-" + attempt.invocationId()), List.of(Measurement.unknown("cost", "USD",
										"No qualified reported cost", attempt.invocationId())),
								"");
					}

					public void close() {
					}
				}, Duration.ofMillis(200), assembly());
	}

	private PreparedItem prepared(Identity identity) throws IOException {
		Path workspace = Files.createDirectories(temp.resolve("workspace-" + identity.replicateIndex()));
		return new PreparedItem(identity, workspace.toString(),
				List.of(Measurement.measured("lineCoverage", "percent", 25, "fixture baseline", "item")), List.of());
	}

	private ClaudeInvocationService.Request request(PreparedItem prepared, String attempt) {
		return new ClaudeInvocationService.Request(prepared, PROMPT, "fixture-model", 5000,
				temp.resolve("attempt-" + attempt).toString(), true);
	}

	private static final Domain MEASURED = new Domain(
			List.of(Measurement.measured("lineCoverage", "percent", 40, "fixture final", "item"),
					Measurement.unknown("mutationScore", "percent", "fixture did not run mutation testing", "item")),
			List.of());

	private Row directRow(Identity identity) throws Exception {
		var prepared = prepared(identity);
		var outcome = claude().invokeDirect(request(prepared, "direct-" + identity.replicateIndex()), 1);
		return DirectExecution.assess(prepared, "COMPLETED", List.of(outcome.invocation()), outcome.workspaceState(),
				MEASURED, (p, d) -> () -> Verdict.single("coverage", Judgment.pass("coverage rose")));
	}

	@Test
	void plainClaudeStyleInvocationYieldsADirectRowWithNoWorkflow() throws Exception {
		var identity = Identity.of("direct-run", "item-1", "plain", 0);
		Row row = directRow(identity);
		var invocation = row.invocations().getFirst();
		var attempt = invocation.attempt();

		assertThat(row.execution()).isEqualTo(RowExecution.DIRECT);
		assertThat(row.executionState()).isEqualTo("COMPLETED");
		assertThat(row.workflowProof()).isEmpty();
		assertThatThrownBy(row::workflowState).isInstanceOf(IllegalStateException.class);
		assertThat(attempt.execution()).isEqualTo(ExecutionKind.DIRECT);
		assertThat(attempt.workflow()).isEmpty();
		assertThat(attempt.attemptNumber()).isEqualTo(1);
		assertThatThrownBy(attempt::workflowRunId).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Direct attempt has no Workflow coordinates");

		// Identity, terminal status, candidate evidence and measurements are kept.
		assertThat(row.identity()).isEqualTo(identity);
		assertThat(attempt.row()).isEqualTo(identity);
		assertThat(attempt.invocationId()).isNotBlank();
		assertThat(invocation.responseState()).isEqualTo("SUCCEEDED");
		assertThat(invocation.responseText()).isEqualTo("Added the null check");
		assertThat(invocation.captureState()).isEqualTo("COMPLETE");
		assertThat(invocation.assemblyState()).isEqualTo("ASSEMBLED");
		assertThat(invocation.providerSessions()).containsExactly("session-" + attempt.invocationId());
		assertThat(invocation.usage()).singleElement().satisfies(m -> {
			assertThat(m.state()).isEqualTo("UNKNOWN");
			assertThat(m.values()).isEmpty();
		});
		assertThat(row.domain()).isEqualTo(MEASURED);
		assertThat(row.judgeState()).isEqualTo("RETURNED");
		assertThat(row.verdicts().getFirst().conclusion()).isEqualTo("PASS");

		// The retained, hash-checked acquisition holds the original request and states
		// direct execution positively, with no Workflow key.
		Evidence.verify(row);
		JsonNode manifest = RuntimeJson.mapper()
			.readTree(Path.of(invocation.acquisitions().getFirst().path()).toFile());
		JsonNode launch = manifest.path("run").path("launch_settings");
		assertThat(launch.path("caller_input").path("prompt").asText()).isEqualTo(PROMPT);
		assertThat(launch.path("model").asText()).isEqualTo("fixture-model");
		JsonNode provenance = launch.path("artifact_provenance");
		assertThat(provenance.path("execution_kind").asText()).isEqualTo("DIRECT");
		assertThat(provenance.path("experiment_invocation_id").asText()).isEqualTo(attempt.invocationId());
		assertThat(provenance.path("attempt_number").asText()).isEqualTo("1");
		assertThat(provenance.fieldNames()).toIterable().noneMatch(name -> name.startsWith("workflow"));
	}

	@Test
	void directFailuresAreRecordedWithoutAWorkflowProof() throws Exception {
		var identity = Identity.of("direct-run", "item-1", "guided", 0);
		var prepared = prepared(identity);
		var failing = new ClaudeInvocationService(
				(request, attempt, directory, budget) -> new ClaudeInvocationService.ProviderCall() {
					public ClaudeInvocationService.Delivery invoke() {
						throw new IllegalStateException("provider refused the session");
					}

					public void close() {
					}
				}, Duration.ofMillis(200), assembly());
		var threw = failing.invokeDirect(request(prepared, "threw"), 1).invocation();
		var replied = new ClaudeInvocationService(
				(request, attempt, directory, budget) -> new ClaudeInvocationService.ProviderCall() {
					public ClaudeInvocationService.Delivery invoke() {
						return new ClaudeInvocationService.Delivery("FAILED", "could not build", List.of(), false,
								List.of(), List.of(Measurement.unknown("cost", "USD", "No qualified reported cost",
										attempt.invocationId())),
								"");
					}

					public void close() {
					}
				}, Duration.ofMillis(200), assembly())
			.invokeDirect(request(prepared, "failed"), 2)
			.invocation();
		assertThat(threw.responseState()).isEqualTo("THREW");
		assertThat(threw.captureState()).isEqualTo("MISSING");
		assertThat(threw.error()).contains("provider refused the session");
		assertThat(replied.responseState()).isEqualTo("FAILED");
		assertThat(List.of(threw.attempt().attemptNumber(), replied.attempt().attemptNumber())).containsExactly(1, 2);

		// Both attempts returned an observed failure: the direct execution completed, and
		// the Jury judges the failed candidate.
		Row failed = DirectExecution.assess(prepared, "COMPLETED", List.of(threw, replied), "RETAINED",
				new Domain(List.of(Measurement.unknown("lineCoverage", "percent", "build failed", "item")), List.of()),
				(p, d) -> () -> Verdict.single("coverage", Judgment.fail("no coverage measured")));
		assertThat(failed.execution()).isEqualTo(RowExecution.DIRECT);
		assertThat(failed.workflowProof()).isEmpty();
		assertThat(failed.verdicts().getFirst().conclusion()).isEqualTo("FAIL");

		// An unconfirmed drain is quarantined: the row is incomplete and not judged.
		var release = new CountDownLatch(1);
		var hanging = new ClaudeInvocationService(
				(request, attempt, directory, budget) -> new ClaudeInvocationService.ProviderCall() {
					public ClaudeInvocationService.Delivery invoke() throws InterruptedException {
						release.await(10, TimeUnit.SECONDS);
						throw new IllegalStateException("released after the test");
					}

					public void close() {
					}
				}, Duration.ofMillis(50), assembly());
		var hangingRequest = new ClaudeInvocationService.Request(prepared, PROMPT, "fixture-model", 50,
				temp.resolve("attempt-hanging").toString(), true);
		var quarantined = catchThrowableOfType(ClaudeInvocationService.QuarantinedInvocationException.class,
				() -> hanging.invokeDirect(hangingRequest, 3));
		release.countDown();
		var outcome = quarantined.outcome();
		assertThat(outcome.invocation().responseState()).isEqualTo("TIMEOUT");
		assertThat(outcome.workspaceState()).isEqualTo("QUARANTINED");
		assertThat(ClaudeInvocationService.readOutcome(Path.of(outcome.evidenceDirectory()))).isEqualTo(outcome);
		Row incomplete = DirectExecution.assess(prepared, "INCOMPLETE", List.of(outcome.invocation()),
				outcome.workspaceState(), MEASURED, (p, d) -> {
					throw new AssertionError("an incomplete direct row is not judged");
				});
		assertThat(incomplete.executionState()).isEqualTo("INCOMPLETE");
		assertThat(incomplete.judgeState()).isEqualTo("NOT_ASSESSED");
		assertThat(incomplete.workspaceState()).isEqualTo("QUARANTINED");
		assertThat(incomplete.workflowProof()).isEmpty();
		Row cancelled = Row.direct(identity, List.of(), "CANCELLED", MEASURED, List.of(), "NOT_ASSESSED",
				"cancelled by the caller", "RETAINED");
		assertThat(cancelled.invocations()).isEmpty();

		var store = new FileSystemResultStore(temp.resolve("results"));
		var result = new Result(AgentRecords.CURRENT_SCHEMA_VERSION, "AGENT_CANONICAL", "direct-run", "direct-failures",
				1, List.of(failed), List.of());
		store.save(result);
		assertThat(new FileSystemResultStore(temp.resolve("results")).load("direct-run")).contains(result);

		// A direct row that claims completion without an observed response is refused.
		var unobserved = new Invocation(Attempt.direct(identity, "interrupted", 4), "UNKNOWN", "UNKNOWN", List.of(),
				"QUARANTINED", List.of(), List.of(), "NOT_ATTEMPTED", List.of(),
				List.of(Measurement.unknown("cost", "USD", "interrupted", "interrupted")), "", "Caller interrupted",
				5000, 10);
		assertThatThrownBy(() -> Row.direct(identity, List.of(unobserved), "COMPLETED", MEASURED, List.of(),
				"NOT_ASSESSED", "", "QUARANTINED"))
			.hasMessageContaining("Completed direct execution requires observed responses");
		assertThat(Row
			.direct(identity, List.of(unobserved), "INCOMPLETE", MEASURED, List.of(), "NOT_ASSESSED", "interrupted",
					"QUARANTINED")
			.executionState()).isEqualTo("INCOMPLETE");
	}

	/** A real Workflow run of the three-stage example, assessed by WorkflowAdapter. */
	private Row workflowRow(Identity identity) throws Exception {
		Path attempts = Files.createDirectories(temp.resolve("workflow-attempts"));
		var task = new Task(identity, temp.toString(), "fixture prompt", "fixture model", 5000, attempts.toString());
		var client = new ClaudeInvocationService(
				(request, attempt, directory, budget) -> new ClaudeInvocationService.ProviderCall() {
					public ClaudeInvocationService.Delivery invoke() {
						return new ClaudeInvocationService.Delivery("SUCCEEDED", "fixture response", List.of(), false,
								List.of(),
								List.of(Measurement.unknown("cost", "USD", "fixture", attempt.invocationId())), "");
					}

					public void close() {
					}
				}, Duration.ofMillis(100), AcquisitionAssembly.journal());
		var prepare = new Prepare(input -> new Prepared(input,
				new PreparedItem(input.identity(), input.workspace(), List.of(), List.of()), input.prompt()));
		var invoke = new Invoke(client);
		var measure = new Measure(input -> MEASURED);
		var definition = Workflows.define("direct-beside-workflow")
			.maxDuration(Duration.ofSeconds(20))
			.then("prepare", prepare)
			.then("invoke", invoke)
			.then("measure", measure)
			.terminate(WorkflowModel.Terminal.SUCCEEDED)
			.build();
		var registry = StepRegistry.builder()
			.register("prepare-v1", prepare)
			.register("invoke-v1", invoke)
			.register("measure-v1", measure)
			.build();
		var execution = new WorkflowAdapter().execute(temp.resolve("workflow"), definition, registry,
				new ExecutionCompatibility("direct-test", "fixture-build", Map.of()), identity, task,
				Duration.ofSeconds(1), client::terminateActive);
		Measured output = (Measured) execution.value();
		return WorkflowAdapter.assess(execution, output.invoked().prepared().item(),
				List.of(output.invoked().outcome().invocation()), output.domain(),
				(p, d) -> () -> Verdict.single("coverage", Judgment.pass("coverage rose")));
	}

	@Test
	void workflowRowKeepsItsProofAndRefusesMismatchedWorkflowEvidence() throws Exception {
		Row row = workflowRow(Identity.of("wf-run", "item-1", "orchestrated", 0));
		var attempt = row.invocations().getFirst().attempt();
		assertThat(row.execution()).isEqualTo(RowExecution.WORKFLOW);
		assertThat(row.workflowState()).isEqualTo("SUCCEEDED").isEqualTo(row.executionState());
		assertThat(row.workflowProof()).singleElement().satisfies(proof -> {
			assertThat(proof.state()).isEqualTo("SUCCEEDED");
			assertThat(proof.runId()).isEqualTo(attempt.workflowRunId());
			assertThat(proof.attempts()).containsExactly(attempt);
		});
		assertThat(attempt.execution()).isEqualTo(ExecutionKind.WORKFLOW);
		assertThat(attempt.workflowCoordinates().runId()).isEqualTo(attempt.workflowRunId());
		Evidence.verify(row);

		// The proof must match the retained snapshot.
		var proof = row.workflowProof().getFirst();
		var otherRun = new WorkflowProof(row.identity(), "another-run", proof.state(), proof.lifecycle(),
				proof.snapshot(), List.of());
		var mismatched = new Row(row.identity(), List.of(), RowExecution.WORKFLOW, "SUCCEEDED", row.domain(),
				row.verdicts(), row.judgeState(), row.error(), row.workspaceState(), List.of(otherRun));
		assertThatThrownBy(() -> Evidence.verify(mismatched)).hasMessageContaining("Workflow snapshot identity/state");
		var otherAttempt = new Attempt(attempt.row(), attempt.invocationId(), attempt.workflowRunId(),
				attempt.workflowInvocationId(), "another-attempt", attempt.attemptNumber());
		assertThatThrownBy(() -> new Row(row.identity(), List.of(rebind(row.invocations().getFirst(), otherAttempt)),
				RowExecution.WORKFLOW, "SUCCEEDED", row.domain(), row.verdicts(), row.judgeState(), row.error(),
				row.workspaceState(), row.workflowProof()))
			.hasMessageContaining("Wrong persisted Workflow attempt");
		// A direct attempt is never a Workflow attempt.
		var direct = Attempt.direct(attempt.row(), attempt.invocationId(), 1);
		assertThatThrownBy(() -> new WorkflowProof(row.identity(), proof.runId(), proof.state(), proof.lifecycle(),
				proof.snapshot(), List.of(direct)))
			.hasMessageContaining("Direct attempt in Workflow proof");
		assertThatThrownBy(() -> DirectExecution.assess(
				new PreparedItem(row.identity(), temp.toString(), List.of(), List.of()), "COMPLETED", row.invocations(),
				"RETAINED", row.domain(), (p, d) -> () -> Verdict.single("x", Judgment.pass("x"))))
			.hasMessageContaining("Workflow attempt in a direct row");
	}

	private static Invocation rebind(Invocation i, Attempt a) {
		return new Invocation(a, i.responseState(), i.processState(), i.exitCodes(), i.captureState(), i.acquisitions(),
				i.canonical(), i.assemblyState(), i.providerSessions(), i.usage(), i.responseText(), i.error(),
				i.providerBudgetMs(), i.elapsedMs(), i.qualifications());
	}

	@Test
	void bothExecutionKindsSurviveWriteOnceStorageAndReadBackInASeparateJvm() throws Exception {
		Row direct = directRow(Identity.of("mixed-run", "item-1", "plain", 0));
		Row workflow = workflowRow(Identity.of("mixed-run", "item-1", "orchestrated", 0));
		var result = new Result(AgentRecords.CURRENT_SCHEMA_VERSION, "AGENT_CANONICAL", "mixed-run", "mixed", 1,
				List.of(direct, workflow), List.of());
		Path root = temp.resolve("results");
		new FileSystemResultStore(root).save(result);
		assertThatThrownBy(() -> new FileSystemResultStore(root).save(result))
			.isInstanceOf(FileAlreadyExistsException.class);

		Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
				System.getProperty("java.class.path"), ReadBack.class.getName(), root.toString(), "mixed-run")
			.redirectErrorStream(true)
			.start();
		String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(child.waitFor(60, TimeUnit.SECONDS)).isTrue();
		assertThat(child.exitValue()).as(output).isZero();
		assertThat(output.lines().toList()).contains("row " + direct.identity().rowId() + " DIRECT COMPLETED 0",
				"row " + workflow.identity().rowId() + " WORKFLOW SUCCEEDED 1");
		String json = output.lines()
			.filter(line -> line.startsWith("json "))
			.findFirst()
			.orElseThrow()
			.substring("json ".length());
		assertThat(RuntimeJson.mapper().readValue(json, Result.class)).isEqualTo(result);
		assertThat(json).isEqualTo(RuntimeJson.mapper().writeValueAsString(result));
	}

	/**
	 * Reads a stored result in its own JVM, verifies each row and prints what it read.
	 */
	public static final class ReadBack {

		public static void main(String[] args) throws Exception {
			var result = new FileSystemResultStore(Path.of(args[0])).load(args[1]).orElseThrow();
			for (Row row : result.items()) {
				Evidence.verify(row);
				System.out.println("row " + row.identity().rowId() + " " + row.execution() + " " + row.executionState()
						+ " " + row.workflowProof().size());
			}
			System.out.println("json " + RuntimeJson.mapper().writeValueAsString(result));
		}

	}

	private static Path schema4(Path store, String fixture) throws IOException {
		Path source = Path.of("src/test/resources/schema4").resolve(fixture);
		String id = RuntimeJson.mapper().readTree(source.toFile()).path("experimentId").asText();
		Path directory = store
			.resolve(Base64.getUrlEncoder().withoutPadding().encodeToString(id.getBytes(StandardCharsets.UTF_8)));
		Files.createDirectories(directory);
		return Files.copy(source, directory.resolve("run.json"));
	}

	@Test
	void schema4RecordsReadBackWithTheirOriginalMeaning() throws Exception {
		Path store = temp.resolve("schema4");
		for (String fixture : List.of("workflow-succeeded.json", "workflow-failed.json",
				"not-started-with-invocation.json")) {
			Path file = schema4(store, fixture);
			JsonNode raw = RuntimeJson.mapper().readTree(file.toFile());
			String id = raw.path("experimentId").asText();
			Result read = new FileSystemResultStore(store).load(id).orElseThrow();
			assertThat(FileSystemResultStore.read(file)).isEqualTo(read);
			assertThat(read.recordingSchemaVersion()).isEqualTo(4);
			Row row = read.items().getFirst();
			JsonNode rawRow = raw.path("items").get(0);
			assertThat(row.workflowState()).isEqualTo(rawRow.path("workflowState").asText());
			assertThat(row.execution()).isEqualTo(rawRow.path("workflowState").asText().equals("NOT_STARTED")
					? RowExecution.NOT_STARTED : RowExecution.WORKFLOW);
			assertThat(row.workflowProof()).hasSize(rawRow.path("workflowProof").size());
			var attempt = row.invocations().getFirst().attempt();
			JsonNode rawAttempt = rawRow.path("invocations").get(0).path("attempt");
			assertThat(attempt.execution()).isEqualTo(ExecutionKind.WORKFLOW);
			assertThat(attempt.invocationId()).isEqualTo(rawAttempt.path("invocationId").asText());
			assertThat(attempt.workflowRunId()).isEqualTo(rawAttempt.path("workflowRunId").asText());
			assertThat(attempt.workflowInvocationId()).isEqualTo(rawAttempt.path("workflowInvocationId").asText());
			assertThat(attempt.workflowAttemptId()).isEqualTo(rawAttempt.path("workflowAttemptId").asText());
			assertThat(attempt.attemptNumber()).isEqualTo(rawAttempt.path("attemptNumber").asInt());
			// Everything outside the execution evidence is the same JSON as stored.
			JsonNode reread = RuntimeJson.mapper().valueToTree(read);
			for (String key : List.of("identity", "domain", "verdicts", "judgeState", "error", "workspaceState"))
				assertThat(reread.path("items").get(0).path(key).toString()).as(key)
					.isEqualTo(rawRow.path(key).toString());
			for (String key : List.of("responseState", "captureState", "usage", "responseText", "providerBudgetMs",
					"elapsedMs"))
				assertThat(reread.at("/items/0/invocations/0").path(key).toString()).as(key)
					.isEqualTo(rawRow.path("invocations").get(0).path(key).toString());
			// A schema 4 record is read, never written back as schema 4.
			assertThatThrownBy(() -> new FileSystemResultStore(temp.resolve("rewrite-" + fixture)).save(read))
				.hasMessageContaining("never written");
		}
		// Schema 4 allowed a not-started row with a Workflow-coordinated invocation and
		// no
		// proof. It reads as exactly that: never as direct execution.
		Row notStarted = new FileSystemResultStore(store).load("v4-not-started").orElseThrow().items().getFirst();
		assertThat(notStarted.execution()).isEqualTo(RowExecution.NOT_STARTED);
		assertThat(notStarted.workflowProof()).isEmpty();
		assertThat(notStarted.invocations().getFirst().attempt().workflowCoordinates())
			.isEqualTo(new WorkflowCoordinates("wf-v4", "logical", "physical"));
		var usage = notStarted.invocations().getFirst().usage();
		assertThat(usage.get(0).state()).isEqualTo("UNKNOWN");
		assertThat(usage.get(0).values()).isEmpty();
		assertThat(usage.get(1).state()).isEqualTo("MEASURED");
		assertThat(usage.get(1).values()).containsExactly(0.0);
	}

	@Test
	void retainedSchema4DeliveryReadsAsTheWorkflowAttemptItWas() throws Exception {
		JsonNode raw = RuntimeJson.mapper()
			.readTree(Path.of("src/test/resources/schema4/workflow-failed.json").toFile())
			.at("/items/0/invocations/0");
		Path directory = Files.createDirectories(temp.resolve("retained-attempt"));
		RuntimeJson.mapper().writeValue(directory.resolve("delivery.json").toFile(), raw);
		Invocation delivery = ClaudeInvocationService.readDelivery(directory);
		assertThat(delivery.attempt().execution()).isEqualTo(ExecutionKind.WORKFLOW);
		assertThat(delivery.attempt().workflowCoordinates())
			.isEqualTo(new WorkflowCoordinates("wf-run-failed", "wf-invocation", "wf-attempt"));
		assertThat(delivery.responseState()).isEqualTo("TIMEOUT");
		assertThat(delivery.usage().getFirst().values()).isEmpty();
	}

	@Test
	void ambiguousOrMalformedExecutionEvidenceIsRejected() throws Exception {
		Row direct = directRow(Identity.of("bad-run", "item-1", "plain", 0));
		var result = new Result(AgentRecords.CURRENT_SCHEMA_VERSION, "AGENT_CANONICAL", "bad-run", "bad", 1,
				List.of(direct), List.of());
		ObjectNode good = RuntimeJson.mapper().valueToTree(result);
		var coordinates = RuntimeJson.mapper().valueToTree(new WorkflowCoordinates("invented", "invented", "invented"));
		Map<String, java.util.function.Consumer<ObjectNode>> mutations = new LinkedHashMap<>();
		mutations.put("Direct attempt carries Workflow coordinates",
				json -> ((ArrayNode) json.at("/items/0/invocations/0/attempt/workflow")).add(coordinates));
		mutations.put("Direct row carries a Workflow attempt", json -> {
			var attempt = (ObjectNode) json.at("/items/0/invocations/0/attempt");
			attempt.put("execution", "WORKFLOW");
			((ArrayNode) attempt.path("workflow")).add(coordinates);
		});
		mutations.put("Workflow proof missing", json -> ((ObjectNode) json.at("/items/0")).put("execution", "WORKFLOW")
			.put("executionState", "SUCCEEDED"));
		mutations.put("Workflow attempt requires its Workflow coordinates",
				json -> ((ObjectNode) json.at("/items/0/invocations/0/attempt")).put("execution", "WORKFLOW"));
		mutations.put("Not-started row carries a direct attempt",
				json -> ((ObjectNode) json.at("/items/0")).put("execution", "NOT_STARTED")
					.put("executionState", "NOT_STARTED"));
		mutations.put("Not-started row carries invocations; record DIRECT or WORKFLOW execution", json -> {
			((ObjectNode) json.at("/items/0")).put("execution", "NOT_STARTED").put("executionState", "NOT_STARTED");
			var attempt = (ObjectNode) json.at("/items/0/invocations/0/attempt");
			attempt.put("execution", "WORKFLOW");
			((ArrayNode) attempt.path("workflow")).add(coordinates);
		});
		mutations.put("Missing creator property 'execution'",
				json -> ((ObjectNode) json.at("/items/0/invocations/0/attempt")).remove("execution"));
		mutations.put("RowExecution", json -> ((ObjectNode) json.at("/items/0")).put("execution", "UNKNOWN"));
		mutations.put("Direct execution state",
				json -> ((ObjectNode) json.at("/items/0")).put("executionState", "SUCCEEDED"));
		mutations.put("Malformed schema 4 row", json -> json.put("recordingSchemaVersion", 4));
		for (var mutation : mutations.entrySet()) {
			ObjectNode changed = good.deepCopy();
			mutation.getValue().accept(changed);
			Path root = temp.resolve("bad-" + Math.abs(mutation.getKey().hashCode()));
			Path directory = root.resolve(
					Base64.getUrlEncoder().withoutPadding().encodeToString("bad-run".getBytes(StandardCharsets.UTF_8)));
			Files.createDirectories(directory);
			RuntimeJson.mapper().writeValue(directory.resolve("run.json").toFile(), changed);
			assertThatThrownBy(() -> new FileSystemResultStore(root).load("bad-run")).as(mutation.getKey())
				.hasStackTraceContaining(mutation.getKey());
		}
		// A schema 4 envelope cannot hold direct execution, even when built in memory.
		assertThatThrownBy(() -> new Result(4, "AGENT_CANONICAL", "bad-run", "bad", 1, List.of(direct), List.of()))
			.hasMessageContaining("Schema 4 cannot record direct execution");
		// A direct attempt's acquisition must not name a Workflow.
		var attempt = direct.invocations().getFirst().attempt();
		Path manifest = Path.of(direct.invocations().getFirst().acquisitions().getFirst().path());
		ObjectNode tree = (ObjectNode) RuntimeJson.mapper().readTree(manifest.toFile());
		((ObjectNode) tree.at("/run/launch_settings/artifact_provenance")).put("workflow_run_id", "invented");
		RuntimeJson.mapper().writeValue(manifest.toFile(), tree);
		assertThatThrownBy(() -> AcquisitionAssembly
			.validateManifest(Evidence.reference(manifest, "acquisition-manifest"), attempt, true))
			.hasMessageContaining("Direct acquisition names a Workflow");
		// And a Workflow attempt cannot claim a direct acquisition.
		var asWorkflow = new Attempt(attempt.row(), attempt.invocationId(), "r", "i", "a", 1);
		((ObjectNode) tree.at("/run/launch_settings/artifact_provenance")).remove("workflow_run_id");
		RuntimeJson.mapper().writeValue(manifest.toFile(), tree);
		assertThatThrownBy(() -> AcquisitionAssembly
			.validateManifest(Evidence.reference(manifest, "acquisition-manifest"), asWorkflow, true))
			.hasMessageContaining("association mismatch");
	}

}
