import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.markpollack.agents.client.*;
import io.github.markpollack.agents.client.journal.JournalExecutionRecorder;
import io.github.markpollack.agents.codex.*;
import io.github.markpollack.journal.ocsf.writer.*;
import io.github.markpollack.journal.tck.ocsf.*;

/** One installed Client invocation, immutable original decision, and separate packaged reader. */
public final class CodexOneRead {
    static final ObjectMapper JSON = new ObjectMapper();
    public static void main(String[] args) throws Exception {
        require(args.length == 12, "output/workspace/CLI/sessions/model/effort/prompt/expected/deadline/java/reader-classpath/reader-source required");
        Path output = Path.of(args[0]).toRealPath();
        require(Files.isRegularFile(output.resolve("submission.json")), "Guard reservation required");
        Path root = Files.createDirectory(output.resolve("call"));
        Path workspace = Path.of(args[1]).toRealPath();
        Path executable = Path.of(args[2]).toRealPath();
        long deadline = Long.parseLong(args[8]);
        require(deadline > System.currentTimeMillis() && deadline-System.currentTimeMillis() <= 240_000,
            "Bounded shared deadline required");
        String prompt = Files.readString(Path.of(args[6]));
        Path decisions = root.resolve("original-decision-store");
        var originalRecorder = new JournalExecutionRecorder(decisions, "codex-one-read");
        var acquired = new CompletableFuture<CodexAcquisition>();
        ExecutionRecorder recorder = new ExecutionRecorder() {
            public java.util.function.Supplier<String> prepare(ExecutionDecision d) { throw new AssertionError(); }
            public java.util.function.Supplier<String> prepare(ExecutionDecision d, Map<String,Object> values) {
                acquired.complete((CodexAcquisition) values.get("codexAcquisition"));
                return originalRecorder.prepare(d);
            }
        };
        var provider = CodexClientProvider.builder().codexPath(executable.toString()).workingDirectory(workspace)
            .nativeSessionsDirectory(Path.of(args[3])).acquisitionDirectory(root.resolve("captures"), "0.166.0")
            .sandbox(io.github.markpollack.agents.codexsdk.types.SandboxMode.READ_ONLY).skipGitCheck(true).build();
        var options = DefaultAgentOptions.builder().workingDirectory(workspace.toString()).model(args[4]).effort(args[5])
            .timeout(Duration.ofMillis(Math.min(120_000, remaining(deadline)-30_000))).build();
        try (var client = AgentClient.builder(provider).defaultOptions(options).defaultWorkingDirectory(workspace)
                .defaultTimeout(Duration.ofSeconds(120)).cleanupTimeout(Duration.ofSeconds(15))
                .executionRecorder(recorder).build()) {
            var execution = client.goal(prompt).runAsync();
            String observedResponse = execution.result().getResult();
            Files.writeString(root.resolve("response.txt"), observedResponse, StandardOpenOption.CREATE_NEW);
            var original = execution.decision().orElseThrow();
            var source = acquired.get(5, TimeUnit.SECONDS);
            require(source.complete(), source.detail());
            Path sealed = source.writeManifest(original);
            awaitDelivery(execution, deadline);
            require(execution.awaitTermination(Duration.ofMillis(Math.min(15_000, remaining(deadline)))).cleanupConfirmed(), "cleanup unconfirmed");

            // The public recorder owns the canonical decision codec. Read its original bytes;
            // do not reconstruct a decision from a fixture or mutable provider response.
            Path originalEvents;
            try (var files = Files.walk(decisions)) {
                var events = files.filter(p -> p.getFileName().toString().equals("events.jsonl")).toList();
                require(events.size() == 1, "ambiguous original decision archive");
                originalEvents = events.getFirst();
            }
            var originalPackage = PackageReader.open(originalEvents);
            var binding = TerminalBindingReader.read(originalPackage.declarations);
            require(JSON.readTree(binding.decision()).required("decisionId").asText().equals(original.decisionId()), "wrong original D");
            Files.writeString(root.resolve("D.json"), binding.decision(), StandardOpenOption.CREATE_NEW);
            var envelope = (ObjectNode) JSON.readTree(originalPackage.declarations.run().member("terminal_binding").member("terminalEnvelope").text());
            Files.write(root.resolve("original-P.json"), canonical(envelope), StandardOpenOption.CREATE_NEW);
            var originalFiles = snapshot(decisions);
            var qualified = io.github.markpollack.journal.tck.ocsf.inventory.CodexAcquisition.qualify(sealed, root.resolve("qualified"));
            var manifest = JSON.readTree(Files.readAllBytes(qualified.manifest()));
            var association = (ObjectNode) JSON.readTree(binding.association());
            association.put("acquisition", "COMPLETED"); association.putNull("priorRevisionId"); association.putArray("missing");
            ArrayNode sources = association.putArray("sources");
            for (var item : manifest.required("sources")) sources.add(occurrence(qualified.manifest().getParent(), item, manifest, original));
            sources.add(occurrence(qualified.manifest().getParent(), JSON.createObjectNode().put("path", "acquisition.json")
                .put("role", "manifest").put("origin", "qualified-codex-manifest"), manifest, original));
            // This separate store receives its initial complete binding exactly once.
            // The original evidence-only package and its binding remain untouched.
            envelope.put("profile", "client-terminal-codex-0.166.0/1");
            envelope.put("associationBytes", Base64.getEncoder().encodeToString(canonical(association)));
            byte[] frozen = canonical(envelope);
            Files.write(root.resolve("P.json"), frozen, StandardOpenOption.CREATE_NEW);
            Path store = root.resolve("archive");
            var receiver = new TerminalReceiver(store, "codex-one-read", WriterOptions.defaults());
            String ack = receiver.receive(frozen, qualified.manifest().getParent());
            require(snapshot(decisions).equals(originalFiles), "original evidence-only package changed");
            try {
                new TerminalReceiver(decisions, "codex-one-read", WriterOptions.defaults()).receive(frozen, qualified.manifest().getParent());
                throw new AssertionError("profile replacement accepted in original receiver");
            } catch (IllegalArgumentException expected) { }
            require(snapshot(decisions).equals(originalFiles), "conflict changed original package");
            var beforeReplay = snapshot(root);
            require(receiver.receive(frozen, qualified.manifest().getParent()).equals(ack), "replay changed ACK");
            require(originalRecorder.prepare(original).get().equals(((TerminalDelivery.Completed) execution.recording().delivery()).bindingId()), "original replay changed binding");
            require(snapshot(root).equals(beforeReplay), "replay changed original files");
            require(execution.decision().orElseThrow() == original, "decision replaced");
            Path events = null;
            for (var artifact : JSON.readTree(ack).required("artifacts")) {
                String path = artifact.required("relativePath").asText();
                if (path.endsWith("/events.jsonl")) events = store.resolve(path);
            }
            require(events != null, "missing final events");
            var proof = JSON.createObjectNode().put("executable", executable.toString()).put("liveQualificationInferred", false)
                .put("executionId", original.executionId()).put("decisionId", original.decisionId())
                .put("sessionId", original.sessionId()).put("connectionId", original.connectionId().orElseThrow())
                .put("nativeSessionId", manifest.required("run").required("session_uid").asText())
                .put("nativeTurnId", manifest.required("turns").get(0).required("native_turn_uid").asText())
                .put("sealedManifest", sealed.toString()).put("events", events.toString())
                .put("originalEvents", originalEvents.toString()).put("originalStore", decisions.toString())
                .put("originalPackageUnchanged", true).put("sameReceiverProfileConflictRejected", true)
                .put("assembly", qualified.assemblyExpectation().toString()).put("originalDecision", root.resolve("D.json").toString())
                .put("bindingId", JSON.readTree(ack).required("bindingId").asText()).put("unchangedReplayFiles", beforeReplay.size());
            JSON.writerWithDefaultPrettyPrinter().writeValue(root.resolve("proof.json").toFile(), proof);
            require(manifest.required("turns").size() == 1, "Expected exactly one native turn");
            Path decoded = root.resolve("reader-D.json");
            Process reader = new ProcessBuilder(args[9], "--class-path", args[10], args[11],
                    root.resolve("proof.json").toString(), decoded.toString())
                .redirectOutput(root.resolve("reader.stdout").toFile()).redirectError(root.resolve("reader.stderr").toFile()).start();
            try {
                require(reader.waitFor(Math.max(1, remaining(deadline)-15_000), TimeUnit.MILLISECONDS), "Reader exceeded shared deadline");
                require(reader.exitValue() == 0, "Independent packaged reader refused: " + reader.exitValue());
            } finally {
                if (reader.isAlive()) { reader.destroyForcibly(); reader.waitFor(Math.min(2000, remaining(deadline)), TimeUnit.MILLISECONDS); }
            }
            var accepted = JSON.createObjectNode().put("kind", "codex-one-read-proof/1")
                .put("readerExit", 0).put("readerExcludedClientSdkWriter", true)
                .put("nativeIdentityMatched", true).put("acquisitionComplete", true).put("sourceVerified", true)
                .put("submissions", 1).put("generations", 1).put("observedResponse", observedResponse)
                .put("costState", "UNKNOWN").put("expectedResponse", args[7]);
            accepted.set("originalDecision", reference(root.resolve("D.json")));
            accepted.set("readerDecision", reference(decoded));
            var retained = accepted.putArray("retainedEvidence");
            try (var files = Files.walk(root)) { for (var path : files.filter(Files::isRegularFile).sorted().toList()) retained.add(reference(path)); }
            Files.write(output.resolve("native-proof.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(accepted), StandardOpenOption.CREATE_NEW);
            System.out.println("ONE_READ_ARCHIVE_ACCEPTED E=" + original.executionId());
        }
    }
    static ObjectNode occurrence(Path base, JsonNode source, JsonNode manifest, ExecutionDecision d) throws Exception {
        String path = source.required("path").asText(); byte[] bytes = Files.readAllBytes(base.resolve(path));
        String stream = Map.of("stdin", "STDIN", "stream", "STDOUT", "stderr", "STDERR").get(source.required("role").asText());
        return JSON.createObjectNode().put("sourceId", path).put("relativePath", path).put("origin", source.required("origin").asText())
            .put("sha256", hash(bytes)).put("size", bytes.length).put("start", 0).put("end", bytes.length).put("stream", stream)
            .put("nativeSessionId", manifest.required("run").required("session_uid").asText()).putNull("nativeTurnId")
            .putNull("nativeConnectionId").put("clientConnectionId", d.connectionId().orElseThrow());
    }
    static byte[] canonical(JsonNode value) throws Exception {
        // The wire codec requires recursively sorted object keys, without extra whitespace.
        return JSON.writeValueAsBytes(sorted(value));
    }
    static JsonNode sorted(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = JSON.createObjectNode(); var fields = new TreeMap<String,JsonNode>();
            value.fields().forEachRemaining(e -> fields.put(e.getKey(), e.getValue()));
            fields.forEach((key, child) -> result.set(key, sorted(child))); return result;
        }
        if (value.isArray()) { ArrayNode result = JSON.createArrayNode(); value.forEach(child -> result.add(sorted(child))); return result; }
        return value;
    }
    static Map<String,String> snapshot(Path root) throws Exception {
        var hashes = new TreeMap<String,String>();
        try (var files = Files.walk(root)) { for (var file : files.filter(Files::isRegularFile).toList()) hashes.put(file.toString(), hash(Files.readAllBytes(file))); }
        return hashes;
    }
    static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    static void awaitDelivery(AgentExecution e, long deadline) throws Exception {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.min(5000, remaining(deadline)));
        while (!(e.recording().delivery() instanceof TerminalDelivery.Completed) && System.nanoTime() < end) Thread.sleep(5);
        require(e.recording().delivery() instanceof TerminalDelivery.Completed, "recording failed: " + e.recording());
    }
    static long remaining(long deadline) {
        long millis = deadline - System.currentTimeMillis();
        require(millis > 0, "Shared deadline exhausted");
        return millis;
    }
    static ObjectNode reference(Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        return JSON.createObjectNode().put("path", path.toRealPath().toString()).put("sha256", hash(bytes)).put("bytes", bytes.length);
    }
    static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
