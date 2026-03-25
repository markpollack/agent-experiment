package io.github.markpollack.experiment.pipeline;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * {@link ProjectAnalyzer} implementation that parses Maven POM files and scans Java
 * source for import patterns. Uses JDK built-in XML parsing (no external dependencies).
 *
 * <p>
 * Analysis includes:
 * <ul>
 * <li>POM parsing: parent coordinates, Boot version, Java version, dependencies,
 * modules</li>
 * <li>Import scanning: javax.* patterns in .java files</li>
 * <li>Config file detection: application.properties/yml in src/main/resources</li>
 * <li>Annotation detection: Spring/JPA annotations via regex in .java files</li>
 * </ul>
 */
public class PomAnalyzer implements ProjectAnalyzer {

	private static final Logger logger = LoggerFactory.getLogger(PomAnalyzer.class);

	private static final Pattern IMPORT_PATTERN = Pattern.compile("^import\\s+(javax\\.[\\w.]+)", Pattern.MULTILINE);

	private static final Pattern ANNOTATION_PATTERN = Pattern.compile("^\\s*@(\\w+)", Pattern.MULTILINE);

	private static final List<String> TRACKED_ANNOTATIONS = List.of("SpringBootApplication", "Entity", "Table",
			"MappedSuperclass", "RestController", "Controller", "Service", "Repository", "Component", "Configuration",
			"EnableAutoConfiguration", "EnableWebSecurity", "Transactional");

	private static final List<String> CONFIG_FILE_NAMES = List.of("application.properties", "application.yml",
			"application.yaml", "bootstrap.properties", "bootstrap.yml", "bootstrap.yaml");

	@Override
	public AnalysisEnvelope analyze(Path workspace, AnalysisConfig config) {
		long startMs = System.currentTimeMillis();

		Path pomPath = workspace.resolve("pom.xml");
		if (!Files.exists(pomPath)) {
			logger.warn("No pom.xml found at {}", workspace);
			return AnalysisEnvelope.builder()
				.projectName(workspace.getFileName().toString())
				.buildTool("unknown")
				.metadata(Map.of("analysisDurationMs", System.currentTimeMillis() - startMs))
				.build();
		}

		try {
			Document doc = parsePom(pomPath);
			Element root = doc.getDocumentElement();

			String projectName = extractProjectName(root, workspace);
			String parentCoordinates = extractParentCoordinates(root);
			String bootVersion = extractBootVersion(root);
			String javaVersion = extractJavaVersion(root);
			Map<String, String> dependencies = extractDependencies(root);
			List<String> modules = extractModules(root);
			Map<String, List<String>> importPatterns = scanImportPatterns(workspace);
			List<AnnotationUsage> annotations = scanAnnotations(workspace);
			List<String> configFiles = findConfigFiles(workspace);

			long durationMs = System.currentTimeMillis() - startMs;
			logger.info(
					"POM analysis of '{}': bootVersion={}, javaVersion={}, {} deps, {} import namespaces, {} annotations, {}ms",
					projectName, bootVersion, javaVersion, dependencies.size(), importPatterns.size(),
					annotations.size(), durationMs);

			return AnalysisEnvelope.builder()
				.projectName(projectName)
				.bootVersion(bootVersion)
				.javaVersion(javaVersion)
				.buildTool("maven")
				.parentCoordinates(parentCoordinates)
				.dependencies(dependencies)
				.importPatterns(importPatterns)
				.annotations(annotations)
				.configFiles(configFiles)
				.modules(modules)
				.metadata(Map.of("analysisDurationMs", durationMs))
				.build();
		}
		catch (Exception ex) {
			throw new AnalysisException("Failed to analyze POM at " + pomPath, ex);
		}
	}

	Document parsePom(Path pomPath) {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			return factory.newDocumentBuilder().parse(pomPath.toFile());
		}
		catch (Exception ex) {
			throw new AnalysisException("Failed to parse POM: " + pomPath, ex);
		}
	}

	String extractProjectName(Element root, Path workspace) {
		String name = getChildText(root, "name");
		if (name != null) {
			return name;
		}
		String artifactId = getChildText(root, "artifactId");
		if (artifactId != null) {
			return artifactId;
		}
		return workspace.getFileName().toString();
	}

	String extractParentCoordinates(Element root) {
		NodeList parentNodes = root.getElementsByTagName("parent");
		if (parentNodes.getLength() == 0) {
			return null;
		}
		Element parent = (Element) parentNodes.item(0);
		String groupId = getChildText(parent, "groupId");
		String artifactId = getChildText(parent, "artifactId");
		String version = getChildText(parent, "version");
		if (groupId != null && artifactId != null && version != null) {
			return groupId + ":" + artifactId + ":" + version;
		}
		return null;
	}

	String extractBootVersion(Element root) {
		// Check parent for spring-boot-starter-parent
		NodeList parentNodes = root.getElementsByTagName("parent");
		if (parentNodes.getLength() > 0) {
			Element parent = (Element) parentNodes.item(0);
			String artifactId = getChildText(parent, "artifactId");
			if ("spring-boot-starter-parent".equals(artifactId)) {
				return getChildText(parent, "version");
			}
		}
		// Check for spring-boot-dependencies in dependencyManagement
		NodeList depMgmt = root.getElementsByTagName("dependencyManagement");
		if (depMgmt.getLength() > 0) {
			NodeList deps = ((Element) depMgmt.item(0)).getElementsByTagName("dependency");
			for (int i = 0; i < deps.getLength(); i++) {
				Element dep = (Element) deps.item(i);
				String aid = getChildText(dep, "artifactId");
				if ("spring-boot-dependencies".equals(aid)) {
					return getChildText(dep, "version");
				}
			}
		}
		return null;
	}

	String extractJavaVersion(Element root) {
		// Check properties for java.version or maven.compiler.source
		NodeList propsNodes = root.getElementsByTagName("properties");
		if (propsNodes.getLength() > 0) {
			Element props = (Element) propsNodes.item(0);
			String javaVersion = getChildText(props, "java.version");
			if (javaVersion != null) {
				return javaVersion;
			}
			String compilerSource = getChildText(props, "maven.compiler.source");
			if (compilerSource != null) {
				return compilerSource;
			}
		}
		return null;
	}

	Map<String, String> extractDependencies(Element root) {
		Map<String, String> deps = new LinkedHashMap<>();
		// Only direct <dependencies> under <project>, not under <dependencyManagement>
		NodeList children = root.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			if (children.item(i) instanceof Element el && "dependencies".equals(el.getTagName())) {
				NodeList depNodes = el.getElementsByTagName("dependency");
				for (int j = 0; j < depNodes.getLength(); j++) {
					Element dep = (Element) depNodes.item(j);
					String groupId = getChildText(dep, "groupId");
					String artifactId = getChildText(dep, "artifactId");
					String version = getChildText(dep, "version");
					if (groupId != null && artifactId != null) {
						String key = groupId + ":" + artifactId;
						deps.put(key, version != null ? version : "managed");
					}
				}
			}
		}
		return deps;
	}

	List<String> extractModules(Element root) {
		List<String> modules = new ArrayList<>();
		NodeList modulesNodes = root.getElementsByTagName("modules");
		if (modulesNodes.getLength() > 0) {
			NodeList moduleNodes = ((Element) modulesNodes.item(0)).getElementsByTagName("module");
			for (int i = 0; i < moduleNodes.getLength(); i++) {
				String module = moduleNodes.item(i).getTextContent().trim();
				if (!module.isEmpty()) {
					modules.add(module);
				}
			}
		}
		return modules;
	}

	Map<String, List<String>> scanImportPatterns(Path workspace) {
		Map<String, List<String>> patterns = new LinkedHashMap<>();
		Path srcMain = workspace.resolve("src/main/java");
		Path srcTest = workspace.resolve("src/test/java");

		if (Files.isDirectory(srcMain)) {
			scanJavaFilesForImports(srcMain, workspace, patterns);
		}
		if (Files.isDirectory(srcTest)) {
			scanJavaFilesForImports(srcTest, workspace, patterns);
		}
		return patterns;
	}

	private void scanJavaFilesForImports(Path sourceDir, Path workspace, Map<String, List<String>> patterns) {
		try {
			Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					if (file.toString().endsWith(".java")) {
						String content = Files.readString(file);
						String relativePath = workspace.relativize(file).toString();
						Matcher matcher = IMPORT_PATTERN.matcher(content);
						while (matcher.find()) {
							String importFqn = matcher.group(1);
							// Group by namespace (first two segments: javax.persistence,
							// javax.validation, etc.)
							String namespace = extractNamespace(importFqn);
							patterns.computeIfAbsent(namespace, k -> new ArrayList<>());
							List<String> files = patterns.get(namespace);
							if (!files.contains(relativePath)) {
								files.add(relativePath);
							}
						}
					}
					return FileVisitResult.CONTINUE;
				}
			});
		}
		catch (IOException ex) {
			logger.warn("Error scanning imports in {}: {}", sourceDir, ex.getMessage());
		}
	}

	String extractNamespace(String importFqn) {
		// javax.persistence.Entity -> javax.persistence
		// javax.validation.constraints.NotBlank -> javax.validation
		// javax.xml.bind.annotation.XmlElement -> javax.xml.bind
		String[] parts = importFqn.split("\\.");
		if (parts.length >= 4 && "xml".equals(parts[1])) {
			// javax.xml.bind -> 4-segment namespace
			return parts[0] + "." + parts[1] + "." + parts[2] + "." + parts[3];
		}
		// javax.persistence, javax.validation -> 2-segment namespace
		return parts.length >= 2 ? parts[0] + "." + parts[1] : importFqn;
	}

	List<AnnotationUsage> scanAnnotations(Path workspace) {
		List<AnnotationUsage> annotations = new ArrayList<>();
		Path srcMain = workspace.resolve("src/main/java");
		if (!Files.isDirectory(srcMain)) {
			return annotations;
		}
		try {
			Files.walkFileTree(srcMain, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					if (file.toString().endsWith(".java")) {
						String content = Files.readString(file);
						String relativePath = workspace.relativize(file).toString();
						String className = extractClassName(file);
						Matcher matcher = ANNOTATION_PATTERN.matcher(content);
						while (matcher.find()) {
							String annotationName = matcher.group(1);
							if (TRACKED_ANNOTATIONS.contains(annotationName)) {
								annotations.add(new AnnotationUsage(annotationName, relativePath, className));
							}
						}
					}
					return FileVisitResult.CONTINUE;
				}
			});
		}
		catch (IOException ex) {
			logger.warn("Error scanning annotations in {}: {}", srcMain, ex.getMessage());
		}
		return annotations;
	}

	List<String> findConfigFiles(Path workspace) {
		List<String> found = new ArrayList<>();
		Path resources = workspace.resolve("src/main/resources");
		if (!Files.isDirectory(resources)) {
			return found;
		}
		for (String configName : CONFIG_FILE_NAMES) {
			Path configPath = resources.resolve(configName);
			if (Files.exists(configPath)) {
				found.add(workspace.relativize(configPath).toString());
			}
		}
		return found;
	}

	private static String getChildText(Element parent, String childTag) {
		NodeList nodes = parent.getChildNodes();
		for (int i = 0; i < nodes.getLength(); i++) {
			if (nodes.item(i) instanceof Element el && childTag.equals(el.getTagName())) {
				String text = el.getTextContent().trim();
				return text.isEmpty() ? null : text;
			}
		}
		return null;
	}

	private static String extractClassName(Path javaFile) {
		String fileName = javaFile.getFileName().toString();
		return fileName.endsWith(".java") ? fileName.substring(0, fileName.length() - 5) : fileName;
	}

}
