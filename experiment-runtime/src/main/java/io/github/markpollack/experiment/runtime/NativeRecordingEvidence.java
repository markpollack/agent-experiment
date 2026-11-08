package io.github.markpollack.experiment.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.markpollack.experiment.runtime.AgentRecords.*;
import io.github.markpollack.journal.tck.ocsf.*;

/**
 * Detached native-terminal validation; deliberately has no Client or writer dependency.
 */
final class NativeRecordingEvidence {

	/**
	 * The CLI route a native recording was captured on. The CLI version is recorded in
	 * {@code profile_id}, not gated: any version on the route is accepted.
	 */
	enum Route {

		CLAUDE_SDK(Set.of("client-terminal-claude-2.1.316/1", "client-terminal-claude-sdk/1"),
				"claude-code/[^/\\s]+/sdk-stdio\\+session-file"),

		CODEX_EXEC(Set.of("client-terminal-codex-0.166.0/1", "client-terminal-codex-exec/1"),
				"codex-cli/[^/\\s]+/exec-stdio\\+rollout");

		private final Set<String> terminalProfiles;

		private final Pattern profileId;

		Route(Set<String> terminalProfiles, String profileId) {
			this.terminalProfiles = terminalProfiles;
			this.profileId = Pattern.compile(profileId);
		}

		static Route ofTerminal(String terminalProfile) throws IOException {
			for (Route route : values())
				if (route.terminalProfiles.contains(terminalProfile))
					return route;
			throw new IOException("Unsupported terminal native profile: " + terminalProfile);
		}

		boolean carries(String profileId) {
			return profileId != null && this.profileId.matcher(profileId).matches();
		}

		void requireProfileId(String profileId) throws IOException {
			require(carries(profileId), "Native profile_id " + profileId + " is not on the " + this + " route");
		}

	}

	private NativeRecordingEvidence() {
	}

	record Facts(JsonNode decision, JsonNode original, JsonNode qualified, JsonNode marker) {
	}

	static boolean verifyIfNative(Invocation invocation) throws IOException {
		if (invocation.canonical().isEmpty())
			return false;
		var canonical = invocation.canonical().getFirst();
		Evidence.verify(canonical);
		var pkg = PackageReader.open(Path.of(canonical.events().path()));
		boolean marker = canonical.retainedFiles()
			.stream()
			.anyMatch(f -> Path.of(f.path()).getFileName().toString().equals("marker.json"));
		boolean binding = pkg.declarations != null && pkg.declarations.run().member("terminal_binding") != null;
		if (!marker && !binding)
			return false;
		verify(invocation);
		return true;
	}

	static Facts verify(Invocation invocation) throws IOException {
		try {
			return checked(invocation);
		}
		catch (IllegalArgumentException | NullPointerException e) {
			throw new IOException("Invalid native terminal archive", e);
		}
	}

	private static Facts checked(Invocation i) throws IOException {
		require(i.responseState().equals("SUCCEEDED") && i.captureState().equals("COMPLETE")
				&& i.assemblyState().equals("ASSEMBLED"), "Native successful projection required");
		require(i.acquisitions().size() == 1 && i.qualifications().size() == 1 && i.canonical().size() == 1,
				"Exact native reference cardinality required");
		var c = i.canonical().getFirst();
		var q = i.qualifications().getFirst();
		require(q.state().equals("QUALIFIED") && q.original().equals(i.acquisitions().getFirst()),
				"Qualification original mismatch");
		Path events = Path.of(c.events().path());
		require(events.getFileName().toString().equals("events.jsonl"), "Exact events filename required");
		Path prepared = events.getParent();
		Path qualifiedRoot = Path.of(q.directory());
		closure(prepared, c.retainedFiles());
		closure(qualifiedRoot, q.retainedFiles());
		Evidence.verify(q.original());
		var pkg = PackageReader.open(events);
		require(pkg.declarations != null && pkg.diagnostics.stream()
			.noneMatch(d -> d.severity() == Diagnostic.Severity.DEFECT || d.severity() == Diagnostic.Severity.REFUSED),
				"Defective or refused native package");
		require(pkg.manifest != null && "COMPLETED".equals(pkg.manifest.packageState())
				&& c.assemblyState().equals(pkg.manifest.packageState()), "Canonical completion projection mismatch");
		var terminal = pkg.declarations.run().member("terminal_binding");
		require(terminal != null, "Missing native terminal binding");
		JsonNode envelope = RuntimeJson.mapper().readTree(terminal.member("terminalEnvelope").text());
		String terminalProfile = envelope.path("profile").asText();
		Route route = Route.ofTerminal(terminalProfile);
		var binding = TerminalBindingReader.read(pkg.declarations);
		require(binding != null && binding.originalBindingId() == null, "One original one-shot binding required");
		JsonNode decision = RuntimeJson.mapper().readTree(binding.decision());
		JsonNode association = RuntimeJson.mapper().readTree(binding.association());
		require("SUCCESS".equals(decision.path("outcome").path("kind").asText()),
				"Non-success decision cannot become success");
		require("COMPLETED".equals(association.path("acquisition").asText()), "Native source acquisition incomplete");
		var attempt = i.attempt();
		var correlation = decision.path("correlation");
		equal(correlation, "experimentRunId", attempt.row().runId());
		equal(correlation, "experimentInvocationId", attempt.invocationId());
		equal(correlation, "workflowRunId", attempt.workflowRunId());
		equal(correlation, "workflowInvocationId", attempt.workflowInvocationId());
		equal(correlation, "workflowAttemptId", attempt.workflowAttemptId());
		String execution = requiredText(decision, "executionId");
		String connection = requiredText(decision, "connectionId");
		JsonNode original = manifest(Path.of(q.original().path()), execution, connection);
		JsonNode qualified = manifest(Path.of(q.manifests().getFirst().path()), execution, connection);
		require(original.path("run").equals(qualified.path("run"))
				&& original.path("process").equals(qualified.path("process")),
				"Qualification changed original run/process identity");
		equal(qualified.path("qualification"), "original_manifest_sha256", q.original().sha256());
		require(Evidence.sha256(qualifiedRoot.resolve("client-acquisition.json")).equals(q.original().sha256()),
				"Original qualification copy changed");
		require(Evidence.sha256(prepared.resolve("sources/acquisition.json")).equals(q.manifests().getFirst().sha256()),
				"Protected qualified acquisition substitution");
		String profileId = requiredText(original, "profile_id");
		equal(qualified, "profile_id", profileId);
		equal(qualified, "profile_id", c.profileId());
		route.requireProfileId(profileId);
		sourceCopies(original, qualified);
		require(i.providerSessions().equals(List.of(requiredText(original.path("run"), "session_uid"))),
				"Native session projection mismatch");
		require(processState(original).equals(i.processState()), "Process state projection mismatch");
		JsonNode exit = original.path("process").path("exit_code");
		require(exit.isNull() || exit.isIntegralNumber(), "Unsupported process exit projection");
		require(i.exitCodes().equals(exit.isNull() ? List.of() : List.of(exit.intValue())),
				"Process exit projection mismatch");
		JsonNode marker = RuntimeJson.mapper().readTree(prepared.resolve("marker.json").toFile());
		equal(marker.path("ack"), "executionId", execution);
		equal(marker.path("ack"), "decisionId", requiredText(decision, "decisionId"));
		require(RuntimeJson.mapper()
			.readTree(marker.path("payload").path("terminalEnvelope").textValue())
			.equals(envelope), "ACK terminal payload differs");
		require(RuntimeJson.mapper()
			.readTree(marker.path("payload").path("association").textValue())
			.equals(association), "ACK association differs");
		requiredText(marker.path("ack"), "bindingId");
		verifyAckArtifacts(prepared, marker.path("ack"));
		Path expectation = Path.of(q.assemblyExpectations().getFirst().path());
		try (var bytes = new ByteArrayOutputStream(); var output = new PrintStream(bytes)) {
			int code = OcsfReaderTool.run(new String[] { "verify", events.toString(),
					prepared.resolve("sources").toString(), "--acquisition",
					prepared.resolve("sources/acquisition.json").toString(), "--assembly", expectation.toString() },
					output, output);
			require(code == 0, "Native source/assembly validation failed: "
					+ bytes.toString(java.nio.charset.StandardCharsets.UTF_8));
		}
		return new Facts(decision, original, qualified, marker);
	}

	private static void verifyAckArtifacts(Path prepared, JsonNode ack) throws IOException {
		Path store = prepared.getParent().getParent();
		Set<Path> observed = new HashSet<>();
		for (JsonNode artifact : ack.path("artifacts")) {
			Path file = safe(store, requiredText(artifact, "relativePath"));
			require(file.startsWith(prepared) && observed.add(file), "Ambiguous ACK artifact");
			require(Evidence.sha256(file).equals(requiredText(artifact, "sha256"))
					&& Files.size(file) == artifact.path("size").longValue(), "ACK artifact changed");
		}
		require(observed.contains(prepared.resolve("events.jsonl"))
				&& observed.contains(prepared.resolve("sources/acquisition.json")),
				"ACK missing canonical/native evidence");
	}

	private static JsonNode manifest(Path path, String execution, String connection) throws IOException {
		JsonNode value = RuntimeJson.mapper().readTree(path.toFile());
		equal(value, "kind", "agent-journal/acquired-execution");
		equal(value, "version", "0.1");
		require(value.path("complete").isBoolean() && value.path("complete").booleanValue(), "Incomplete manifest");
		equal(value, "acquisition_uid", execution);
		equal(value.path("run"), "run_uid", execution);
		equal(value.path("process"), "connection_uid", connection);
		Set<String> seen = new HashSet<>();
		for (JsonNode source : value.path("sources")) {
			String relative = requiredText(source, "path");
			require(seen.add(relative), "Duplicate source path");
			Path file = safe(path.getParent(), relative);
			require(Evidence.sha256(file).equals(requiredText(source, "sha256"))
					&& Files.size(file) == source.path("bytes").longValue(), "Native source changed");
		}
		require(!seen.isEmpty(), "Missing native sources");
		return value;
	}

	private static void sourceCopies(JsonNode original, JsonNode qualified) throws IOException {
		require(original.path("sources").size() == qualified.path("sources").size(),
				"Qualification source cardinality changed");
		for (int n = 0; n < original.path("sources").size(); n++) {
			JsonNode a = original.path("sources").get(n), b = qualified.path("sources").get(n);
			for (String key : List.of("path", "role", "origin", "sha256", "bytes", "acquisition"))
				require(a.path(key).equals(b.path(key)), "Qualification source substitution: " + key);
		}
	}

	static void closure(Path root, List<FileRef> refs) throws IOException {
		root = root.toAbsolutePath().normalize();
		require(root.equals(root.toRealPath()), "Linked closure root refused");
		Set<Path> expected = new HashSet<>();
		for (FileRef ref : refs) {
			Path p = Path.of(ref.path()).toAbsolutePath().normalize();
			require(p.startsWith(root) && expected.add(p), "Escaping/duplicate closure path");
			Evidence.verify(ref);
		}
		Set<Path> actual = new HashSet<>();
		try (var paths = Files.walk(root)) {
			for (Path p : paths.toList()) {
				require(!Files.isSymbolicLink(p), "Linked closure path refused");
				if (Files.isRegularFile(p))
					actual.add(p);
			}
		}
		require(actual.equals(expected), "Incomplete retained closure");
	}

	static Path safe(Path root, String relative) throws IOException {
		root = root.toAbsolutePath().normalize();
		Path name = Path.of(relative);
		require(!name.isAbsolute(), "Absolute source path refused");
		Path file = root.resolve(name).normalize();
		require(file.startsWith(root) && root.equals(root.toRealPath()) && file.equals(file.toRealPath()),
				"Escaping/linked source path");
		return file;
	}

	static String processState(JsonNode manifest) throws IOException {
		if (Route.CODEX_EXEC.carries(manifest.path("profile_id").asText(null))) {
			JsonNode process = manifest.path("process");
			require(process.path("sealed").asBoolean(false) && process.path("exit_code").isIntegralNumber()
					&& process.path("observed_resources_terminated").asBoolean(false),
					"Codex process settlement is incomplete");
			return "sealed_exit_observed";
		}
		return requiredText(manifest.path("process"), "status");
	}

	static String requiredText(JsonNode object, String key) throws IOException {
		JsonNode value = object.path(key);
		require(value.isTextual() && !value.textValue().isBlank(), "Missing " + key);
		return value.textValue();
	}

	static void equal(JsonNode object, String key, String expected) throws IOException {
		require(expected.equals(requiredText(object, key)), "Native binding mismatch: " + key);
	}

	static void require(boolean value, String reason) throws IOException {
		if (!value)
			throw new IOException(reason);
	}

}
