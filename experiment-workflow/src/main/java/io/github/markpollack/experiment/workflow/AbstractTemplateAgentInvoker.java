package io.github.markpollack.experiment.workflow;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.markpollack.experiment.agent.AgentInvocationException;
import io.github.markpollack.experiment.agent.AgentInvoker;
import io.github.markpollack.experiment.agent.InvocationContext;
import io.github.markpollack.experiment.agent.InvocationResult;
import io.github.markpollack.journal.claude.PhaseCapture;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for agent invokers that need pre/post hooks around agent execution.
 *
 * <p>
 * Provides the template-method workflow:
 * <ol>
 * <li>Pre-invoke hook (compile check, baseline measurement)</li>
 * <li>Knowledge-file staging into the workspace</li>
 * <li>Agent invocation (subclass-specific)</li>
 * <li>Post-invoke hook (final measurement, metadata enrichment)</li>
 * </ol>
 *
 * <p>
 * Subclasses implement {@link #invokeAgent(InvocationContext)} to define how the agent is
 * actually called.
 *
 * <h2>Extension points</h2>
 * <ol>
 * <li>Override {@link #preInvoke(Path, InvocationContext)} for domain-specific setup</li>
 * <li>Implement {@link #invokeAgent(InvocationContext)} for agent interaction</li>
 * <li>Override {@link #postInvoke(Path, InvocationContext, Map)} for domain-specific
 * measurement</li>
 * </ol>
 *
 * <h2>Usage</h2> <pre>{@code
 * public class MyAgentInvoker extends AbstractTemplateAgentInvoker {
 *
 *     \@Override
 *     protected AgentResult invokeAgent(InvocationContext context) throws Exception {
 *         // call your agent here and return phase captures
 *         return new AgentResult(phases, sessionId);
 *     }
 * }
 * }</pre>
 *
 * @see WorkflowAgentInvoker
 */
public abstract class AbstractTemplateAgentInvoker implements AgentInvoker {

	private static final Logger logger = LoggerFactory.getLogger(AbstractTemplateAgentInvoker.class);

	@Nullable
	private final Path knowledgeSourceDir;

	@Nullable
	private final List<String> knowledgeFiles;

	protected AbstractTemplateAgentInvoker() {
		this(null, null);
	}

	protected AbstractTemplateAgentInvoker(@Nullable Path knowledgeSourceDir, @Nullable List<String> knowledgeFiles) {
		this.knowledgeSourceDir = knowledgeSourceDir;
		this.knowledgeFiles = knowledgeFiles;
	}

	@Override
	public final InvocationResult invoke(InvocationContext context) throws AgentInvocationException {
		long startTime = System.currentTimeMillis();
		Path workspace = context.workspacePath();

		String itemSlug = context.metadata().getOrDefault("itemId", workspace.getFileName().toString());
		logger.info("=== Agent Invocation: {} ===", itemSlug);

		Map<String, String> metrics = preInvoke(workspace, context);

		copyKnowledge(workspace);

		AgentResult agentResult;
		try {
			agentResult = invokeAgent(context);
		}
		catch (Exception ex) {
			logger.error("Agent execution failed", ex);
			return InvocationResult.error("Agent execution failed: " + ex.getMessage(), context.metadata());
		}

		Map<String, String> enrichedMetadata = new HashMap<>(context.metadata());
		enrichedMetadata.putAll(metrics);
		postInvoke(workspace, context, enrichedMetadata);

		long durationMs = System.currentTimeMillis() - startTime;

		return InvocationResult.fromPhases(agentResult.phases(), durationMs, agentResult.sessionId(), enrichedMetadata);
	}

	/**
	 * Pre-invoke hook. Override to add domain-specific setup such as compile checks,
	 * baseline measurements, or workspace preparation.
	 * @param workspace the item workspace directory
	 * @param context invocation context
	 * @return metrics map to be merged into result metadata
	 */
	protected Map<String, String> preInvoke(Path workspace, InvocationContext context) {
		return Map.of();
	}

	/**
	 * Invoke the agent and return phase captures.
	 * @param context invocation context with workspace, prompt, model
	 * @return agent result with phase captures and optional session ID
	 * @throws Exception on agent failure (wrapped in AgentInvocationException by caller)
	 */
	protected abstract AgentResult invokeAgent(InvocationContext context) throws Exception;

	/**
	 * Post-invoke hook. Override to add domain-specific measurement such as final
	 * coverage, quality metrics, or output validation.
	 * @param workspace the item workspace directory
	 * @param context invocation context
	 * @param metadata mutable metadata map to enrich with measurements
	 */
	protected void postInvoke(Path workspace, InvocationContext context, Map<String, String> metadata) {
	}

	/**
	 * Copy knowledge files into the workspace for the agent to discover. If
	 * {@code knowledgeFiles} contains {@code "index.md"}, copies the entire tree.
	 * Otherwise copies only the listed files preserving relative paths.
	 */
	void copyKnowledge(Path workspace) {
		if (knowledgeSourceDir == null || knowledgeFiles == null || knowledgeFiles.isEmpty()) {
			return;
		}

		Path targetDir = workspace.resolve("knowledge");

		if (knowledgeFiles.contains("index.md")) {
			logger.info("Copying full knowledge tree from {}", knowledgeSourceDir);
			copyDirectoryRecursively(knowledgeSourceDir, targetDir);
		}
		else {
			logger.info("Copying {} targeted knowledge files", knowledgeFiles.size());
			for (String relativePath : knowledgeFiles) {
				Path source = knowledgeSourceDir.resolve(relativePath);
				Path target = targetDir.resolve(relativePath);
				try {
					Files.createDirectories(target.getParent());
					Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
					logger.debug("Copied knowledge file: {}", relativePath);
				}
				catch (IOException ex) {
					throw new UncheckedIOException("Failed to copy knowledge file: " + relativePath, ex);
				}
			}
		}
	}

	private void copyDirectoryRecursively(Path source, Path target) {
		try {
			Files.walkFileTree(source, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
					Files.createDirectories(target.resolve(source.relativize(dir)));
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
					return FileVisitResult.CONTINUE;
				}
			});
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Failed to copy knowledge directory: " + source, ex);
		}
	}

	/**
	 * Result from the agent invocation phase, carrying phase captures and session ID.
	 */
	protected record AgentResult(List<PhaseCapture> phases, @Nullable String sessionId) {
	}

}
