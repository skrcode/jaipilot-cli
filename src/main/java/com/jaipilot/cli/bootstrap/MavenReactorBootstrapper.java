package com.jaipilot.cli.bootstrap;

import com.jaipilot.cli.report.model.VerificationIssue;
import com.jaipilot.cli.util.XmlDocumentLoader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public final class MavenReactorBootstrapper {

    private static final String PITEST_JUNIT5_PLUGIN_VERSION = "1.2.2";

    public MirrorBuild prepare(Path projectRoot, String jacocoVersion, String pitVersion) throws IOException {
        Path rootPom = projectRoot.resolve("pom.xml");
        PomModel rootModel = readPom(rootPom);
        LinkedHashMap<Path, ReactorModule> modulesByPom = discoverModules(projectRoot);
        validateModules(projectRoot, modulesByPom.values());
        Path pitHistoryRoot = preparePitHistoryRoot(projectRoot);

        Path tempProjectRoot = Files.createTempDirectory("jaipilot-verify-");
        Set<Path> reactorPomPaths = modulesByPom.values().stream()
                .map(ReactorModule::relativePomPath)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);

        mirrorWorkspace(projectRoot, tempProjectRoot, reactorPomPaths);

        for (ReactorModule module : modulesByPom.values()) {
            rewritePom(
                    module,
                    projectRoot,
                    tempProjectRoot.resolve(module.relativePomPath()),
                    jacocoVersion,
                    pitVersion,
                    pitHistoryRoot,
                    module.rootModule() && "pom".equalsIgnoreCase(rootModel.packaging())
            );
        }

        return new MirrorBuild(
                projectRoot,
                tempProjectRoot,
                tempProjectRoot.resolve("pom.xml"),
                List.copyOf(modulesByPom.values()),
                "pom".equalsIgnoreCase(rootModel.packaging())
        );
    }

    private LinkedHashMap<Path, ReactorModule> discoverModules(Path projectRoot) {
        LinkedHashMap<Path, ReactorModule> modules = new LinkedHashMap<>();
        ArrayDeque<Path> queue = new ArrayDeque<>();
        queue.add(projectRoot.resolve("pom.xml"));

        while (!queue.isEmpty()) {
            Path pomPath = queue.removeFirst().normalize();
            if (modules.containsKey(pomPath)) {
                continue;
            }

            PomModel model = readPom(pomPath);
            Path moduleDir = pomPath.getParent();
            Path relativeModuleDir = projectRoot.relativize(moduleDir);
            boolean hasMainJava = hasJavaFiles(moduleDir.resolve("src/main/java"));
            boolean hasTestJava = hasJavaFiles(moduleDir.resolve("src/test/java"));
            boolean javaModule = !"pom".equalsIgnoreCase(model.packaging()) && (hasMainJava || hasTestJava);

            modules.put(pomPath, new ReactorModule(
                    model.artifactId(),
                    pomPath,
                    projectRoot.relativize(pomPath),
                    moduleDir,
                    relativeModuleDir,
                    model.packaging(),
                    javaModule,
                    javaModule ? detectTestFramework(moduleDir, model) : TestFramework.NONE
            ));

            for (String moduleEntry : model.modules()) {
                Path candidate = moduleDir.resolve(moduleEntry).normalize();
                Path childPom = candidate.getFileName() != null
                        && candidate.getFileName().toString().endsWith(".xml")
                        ? candidate
                        : candidate.resolve("pom.xml");
                if (!Files.isRegularFile(childPom)) {
                    throw new BootstrapException(new VerificationIssue(
                            "Module pom.xml could not be found for reactor entry `" + moduleEntry + "`.",
                            "Ensure every `<module>` entry resolves to an existing pom.xml before running `jaipilot verify`."
                    ));
                }
                queue.add(childPom);
            }
        }

        return modules;
    }

    private void validateModules(Path projectRoot, Iterable<ReactorModule> modules) {
        List<String> outsideRoot = new ArrayList<>();
        List<String> missingFramework = new ArrayList<>();
        List<String> unsupportedFramework = new ArrayList<>();

        for (ReactorModule module : modules) {
            if (!module.originalModuleDir().startsWith(projectRoot)) {
                outsideRoot.add(module.originalModuleDir().toString());
                continue;
            }
            if (!module.javaModule()) {
                continue;
            }
            if (module.testFramework() == TestFramework.NONE) {
                missingFramework.add(module.moduleLabel());
            } else if (module.testFramework() == TestFramework.UNSUPPORTED) {
                unsupportedFramework.add(module.moduleLabel());
            }
        }

        if (!outsideRoot.isEmpty()) {
            throw new BootstrapException(new VerificationIssue(
                    "Modules outside the requested project root are not supported by the mirrored-reactor bootstrap.",
                    "Move the external modules under the project root or run `jaipilot verify` against a standard in-repo Maven reactor. Modules: "
                            + String.join(", ", outsideRoot)
            ));
        }

        if (!missingFramework.isEmpty()) {
            throw new BootstrapException(new VerificationIssue(
                    "No supported JUnit 4 or JUnit 5 tests were detected for one or more Java modules.",
                    "Add JUnit 4 or JUnit 5 tests for the listed modules before running mutation verification. Modules: "
                            + String.join(", ", missingFramework)
            ));
        }

        if (!unsupportedFramework.isEmpty()) {
            throw new BootstrapException(new VerificationIssue(
                    "Only JUnit 4 and JUnit 5 are supported for zero-config PIT bootstrap in this version.",
                    "Migrate the listed modules to JUnit 4 or JUnit 5, or configure the project manually outside JAIPilot. Modules: "
                            + String.join(", ", unsupportedFramework)
            ));
        }
    }

    private void mirrorWorkspace(Path projectRoot, Path tempProjectRoot, Set<Path> reactorPomPaths) throws IOException {
        Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relativePath = projectRoot.relativize(dir);
                if (!relativePath.toString().isBlank()) {
                    String directoryName = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (".git".equals(directoryName) || "target".equals(directoryName)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Files.createDirectories(tempProjectRoot.resolve(relativePath));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relativePath = projectRoot.relativize(file);
                if (reactorPomPaths.contains(relativePath)) {
                    return FileVisitResult.CONTINUE;
                }

                Path targetPath = tempProjectRoot.resolve(relativePath);
                Files.createDirectories(targetPath.getParent());

                if (shouldCopy(relativePath)) {
                    Files.copy(file, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    if (Files.isExecutable(file)) {
                        targetPath.toFile().setExecutable(true, false);
                    }
                } else {
                    try {
                        Files.createSymbolicLink(targetPath, file.toAbsolutePath());
                    } catch (IOException | UnsupportedOperationException exception) {
                        Files.copy(file, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private boolean shouldCopy(Path relativePath) {
        String normalized = relativePath.toString().replace('\\', '/');
        return normalized.equals("mvnw")
                || normalized.equals("mvnw.cmd")
                || normalized.startsWith(".mvn/");
    }

    private void rewritePom(
            ReactorModule module,
            Path projectRoot,
            Path targetPomPath,
            String jacocoVersion,
            String pitVersion,
            Path pitHistoryRoot,
            boolean rootAggregate
    ) throws IOException {
        Document document = XmlDocumentLoader.load(module.originalPomPath());
        Element project = document.getDocumentElement();
        if (module.javaModule() || rootAggregate) {
            ensurePlugin(project, "org.jacoco", "jacoco-maven-plugin", jacocoVersion, false);
        }
        if (module.javaModule()) {
            Element pitPlugin = ensurePlugin(project, "org.pitest", "pitest-maven", pitVersion, true);
            if (module.testFramework() == TestFramework.JUNIT5) {
                ensurePluginDependency(
                        pitPlugin,
                        "org.pitest",
                        "pitest-junit5-plugin",
                        PITEST_JUNIT5_PLUGIN_VERSION
                );
            }
            configurePitPlugin(pitPlugin, module, pitHistoryRoot);
        }
        writeDocument(document, targetPomPath);
    }

    private void configurePitPlugin(Element pitPlugin, ReactorModule module, Path pitHistoryRoot) {
        if (pitHistoryRoot == null) {
            return;
        }

        Path historyFile = pitHistoryRoot.resolve(historyFileName(module));
        Element configuration = child(pitPlugin, "configuration", true);
        upsertText(configuration, "historyInputFile", historyFile.toString());
        upsertText(configuration, "historyOutputFile", historyFile.toString());
    }

    private Path preparePitHistoryRoot(Path projectRoot) {
        String userHome = System.getProperty("user.home", "").trim();
        if (userHome.isEmpty()) {
            return null;
        }

        Path historyRoot = Path.of(userHome, ".jaipilot", "pit-history", projectFingerprint(projectRoot));
        try {
            Files.createDirectories(historyRoot);
            return historyRoot;
        } catch (IOException | SecurityException exception) {
            return null;
        }
    }

    private String historyFileName(ReactorModule module) {
        return sanitizeFileName(module.relativePomPath().toString()) + ".bin";
    }

    private String sanitizeFileName(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String projectFingerprint(Path projectRoot) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] fingerprint = digest.digest(projectRoot.toAbsolutePath().normalize()
                    .toString()
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(fingerprint, 0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private Element ensurePlugin(
            Element project,
            String groupId,
            String artifactId,
            String version,
            boolean ensureConfiguration
    ) {
        Element build = child(project, "build", true);
        Element plugins = child(build, "plugins", true);
        Element plugin = findPlugin(plugins, groupId, artifactId);
        if (plugin == null) {
            plugin = project.getOwnerDocument().createElement("plugin");
            plugins.appendChild(plugin);
        }
        upsertText(plugin, "groupId", groupId);
        upsertText(plugin, "artifactId", artifactId);
        upsertText(plugin, "version", version);
        if (ensureConfiguration) {
            child(plugin, "configuration", true);
        }
        return plugin;
    }

    private void ensurePluginDependency(
            Element plugin,
            String groupId,
            String artifactId,
            String version
    ) {
        Element dependencies = child(plugin, "dependencies", true);
        Element dependency = findDependency(dependencies, groupId, artifactId);
        if (dependency == null) {
            dependency = plugin.getOwnerDocument().createElement("dependency");
            dependencies.appendChild(dependency);
        }
        upsertText(dependency, "groupId", groupId);
        upsertText(dependency, "artifactId", artifactId);
        upsertText(dependency, "version", version);
    }

    private PomModel readPom(Path pomPath) {
        Document document = XmlDocumentLoader.load(pomPath);
        Element project = document.getDocumentElement();
        List<String> modules = new ArrayList<>();
        NodeList moduleNodes = project.getElementsByTagName("module");
        for (int index = 0; index < moduleNodes.getLength(); index++) {
            modules.add(moduleNodes.item(index).getTextContent().trim());
        }

        Set<String> dependencyCoordinates = new LinkedHashSet<>();
        NodeList dependencyNodes = project.getElementsByTagName("dependency");
        for (int index = 0; index < dependencyNodes.getLength(); index++) {
            Node node = dependencyNodes.item(index);
            if (!(node instanceof Element dependency)) {
                continue;
            }
            String groupId = text(dependency, "groupId");
            String artifactId = text(dependency, "artifactId");
            if (!groupId.isBlank() && !artifactId.isBlank()) {
                dependencyCoordinates.add(groupId + ":" + artifactId);
            }
        }

        String packaging = directText(project, "packaging");
        if (packaging.isBlank()) {
            packaging = "jar";
        }

        String artifactId = directText(project, "artifactId");
        if (artifactId.isBlank()) {
            artifactId = pomPath.getParent().getFileName().toString();
        }

        return new PomModel(
                artifactId,
                packaging,
                modules,
                dependencyCoordinates
        );
    }

    private TestFramework detectTestFramework(Path moduleDir, PomModel model) {
        boolean hasTestSources = hasJavaFiles(moduleDir.resolve("src/test/java"));
        if (!hasTestSources) {
            return TestFramework.NONE;
        }

        boolean junit5Dependency = model.dependencyCoordinates().stream().anyMatch(coordinate ->
                coordinate.startsWith("org.junit.jupiter:")
                        || coordinate.startsWith("org.junit.platform:")
                        || coordinate.startsWith("org.junit.vintage:")
        );
        boolean junit4Dependency = model.dependencyCoordinates().contains("junit:junit");
        boolean testNgDependency = model.dependencyCoordinates().stream().anyMatch(coordinate ->
                coordinate.startsWith("org.testng:")
        );

        ImportScanResult imports = scanTestImports(moduleDir.resolve("src/test/java"));
        if (imports.junit5Imports() || junit5Dependency) {
            return TestFramework.JUNIT5;
        }
        if (imports.junit4Imports() || junit4Dependency) {
            return TestFramework.JUNIT4;
        }
        if (imports.unsupportedImports() || testNgDependency) {
            return TestFramework.UNSUPPORTED;
        }
        return TestFramework.NONE;
    }

    private ImportScanResult scanTestImports(Path testSourceRoot) {
        boolean junit4 = false;
        boolean junit5 = false;
        boolean unsupported = false;

        if (!Files.isDirectory(testSourceRoot)) {
            return new ImportScanResult(false, false, false);
        }

        try (var paths = Files.walk(testSourceRoot)) {
            for (Path file : (Iterable<Path>) paths.filter(path -> path.toString().endsWith(".java"))::iterator) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                String normalized = content.toLowerCase(Locale.ROOT);
                junit5 |= normalized.contains("org.junit.jupiter.")
                        || normalized.contains("org.junit.platform.");
                junit4 |= normalized.contains("org.junit.test")
                        || normalized.contains("org.junit.before")
                        || normalized.contains("org.junit.after")
                        || normalized.contains("org.junit.runner")
                        || normalized.contains("org.junit.assert");
                unsupported |= normalized.contains("org.testng.")
                        || normalized.contains("spock.lang.")
                        || normalized.contains("org.junitpioneer.");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to inspect test sources under " + testSourceRoot, exception);
        }

        return new ImportScanResult(junit4, junit5, unsupported);
    }

    private boolean hasJavaFiles(Path directory) {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (var paths = Files.walk(directory)) {
            return paths.anyMatch(path -> path.toString().endsWith(".java"));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to scan Java sources under " + directory, exception);
        }
    }

    private Element findPlugin(Element plugins, String groupId, String artifactId) {
        NodeList pluginNodes = plugins.getChildNodes();
        for (int index = 0; index < pluginNodes.getLength(); index++) {
            Node node = pluginNodes.item(index);
            if (!(node instanceof Element plugin) || !"plugin".equals(plugin.getTagName())) {
                continue;
            }
            String pluginGroupId = directText(plugin, "groupId");
            String pluginArtifactId = directText(plugin, "artifactId");
            if (Objects.equals(groupId, pluginGroupId) && Objects.equals(artifactId, pluginArtifactId)) {
                return plugin;
            }
        }
        return null;
    }

    private Element findDependency(Element dependencies, String groupId, String artifactId) {
        NodeList dependencyNodes = dependencies.getChildNodes();
        for (int index = 0; index < dependencyNodes.getLength(); index++) {
            Node node = dependencyNodes.item(index);
            if (!(node instanceof Element dependency) || !"dependency".equals(dependency.getTagName())) {
                continue;
            }
            String dependencyGroupId = directText(dependency, "groupId");
            String dependencyArtifactId = directText(dependency, "artifactId");
            if (Objects.equals(groupId, dependencyGroupId) && Objects.equals(artifactId, dependencyArtifactId)) {
                return dependency;
            }
        }
        return null;
    }

    private Element child(Element parent, String tagName, boolean create) {
        NodeList nodeList = parent.getChildNodes();
        for (int index = 0; index < nodeList.getLength(); index++) {
            Node node = nodeList.item(index);
            if (node instanceof Element element && tagName.equals(element.getTagName())) {
                return element;
            }
        }
        if (!create) {
            return null;
        }
        Element child = parent.getOwnerDocument().createElement(tagName);
        parent.appendChild(child);
        return child;
    }

    private void upsertText(Element parent, String tagName, String value) {
        Element child = child(parent, tagName, true);
        child.setTextContent(value);
    }

    private String directText(Element parent, String tagName) {
        Element child = child(parent, tagName, false);
        return child == null ? "" : child.getTextContent().trim();
    }

    private String text(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return "";
        }
        return nodes.item(0).getTextContent().trim();
    }

    private void writeDocument(Document document, Path targetPomPath) throws IOException {
        try {
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
            Files.createDirectories(targetPomPath.getParent());
            transformer.transform(new DOMSource(document), new StreamResult(targetPomPath.toFile()));
        } catch (TransformerException exception) {
            throw new IOException("Failed to write mirrored pom.xml to " + targetPomPath, exception);
        }
    }

    private record PomModel(
            String artifactId,
            String packaging,
            List<String> modules,
            Set<String> dependencyCoordinates
    ) {
    }

    private record ImportScanResult(
            boolean junit4Imports,
            boolean junit5Imports,
            boolean unsupportedImports
    ) {
    }
}
