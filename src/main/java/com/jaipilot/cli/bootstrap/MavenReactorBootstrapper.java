package com.jaipilot.cli.bootstrap;

import com.jaipilot.cli.report.model.VerificationIssue;
import com.jaipilot.cli.util.XmlDocumentLoader;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.xml.XMLConstants;
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

    public MirrorBuild prepare(Path projectRoot, String jacocoVersion) throws IOException {
        Path rootPom = projectRoot.resolve("pom.xml");
        PomModel rootModel = readPom(rootPom);
        LinkedHashMap<Path, ReactorModule> modulesByPom = discoverModules(projectRoot);
        validateModules(projectRoot, modulesByPom.values());

        Path tempProjectRoot = Files.createTempDirectory("jaipilot-verify-");
        Set<Path> reactorPomPaths = modulesByPom.values().stream()
                .map(ReactorModule::relativePomPath)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);

        mirrorWorkspace(projectRoot, tempProjectRoot, reactorPomPaths);

        for (ReactorModule module : modulesByPom.values()) {
            rewritePom(
                    module,
                    tempProjectRoot.resolve(module.relativePomPath()),
                    jacocoVersion,
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
                    javaModule
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

        for (ReactorModule module : modules) {
            if (!module.originalModuleDir().startsWith(projectRoot)) {
                outsideRoot.add(module.originalModuleDir().toString());
            }
        }

        if (!outsideRoot.isEmpty()) {
            throw new BootstrapException(new VerificationIssue(
                    "Modules outside the requested project root are not supported by the mirrored-reactor bootstrap.",
                    "Move the external modules under the project root or run `jaipilot verify` against a standard in-repo Maven reactor. Modules: "
                            + String.join(", ", outsideRoot)
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
            Path targetPomPath,
            String jacocoVersion,
            boolean rootAggregate
    ) throws IOException {
        Document document = XmlDocumentLoader.load(module.originalPomPath());
        Element project = document.getDocumentElement();
        if (module.javaModule() || rootAggregate) {
            ensurePlugin(project, "org.jacoco", "jacoco-maven-plugin", jacocoVersion);
        }
        writeDocument(document, targetPomPath);
    }

    private Element ensurePlugin(
            Element project,
            String groupId,
            String artifactId,
            String version
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
        return plugin;
    }

    private PomModel readPom(Path pomPath) {
        Document document = XmlDocumentLoader.load(pomPath);
        Element project = document.getDocumentElement();
        List<String> modules = new ArrayList<>();
        NodeList moduleNodes = project.getElementsByTagName("module");
        for (int index = 0; index < moduleNodes.getLength(); index++) {
            modules.add(moduleNodes.item(index).getTextContent().trim());
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
                modules
        );
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
            List<String> modules
    ) {
    }
}
