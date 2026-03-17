package com.jaipilot.cli.report;

import com.jaipilot.cli.report.model.CoverageCounter;
import com.jaipilot.cli.report.model.CoverageMetric;
import com.jaipilot.cli.report.model.JacocoLineCoverage;
import com.jaipilot.cli.report.model.JacocoMethodCoverage;
import com.jaipilot.cli.report.model.JacocoReport;
import com.jaipilot.cli.report.model.JacocoSourceFileCoverage;
import com.jaipilot.cli.util.SourcePathResolver;
import com.jaipilot.cli.util.XmlDocumentLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public final class JacocoReportParser {

    public Optional<JacocoReport> parse(Path buildRoot, Path originalProjectRoot) {
        List<Path> reportPaths = locateReports(buildRoot);
        if (reportPaths.isEmpty()) {
            return Optional.empty();
        }

        List<JacocoSourceFileCoverage> sourceFiles = new ArrayList<>();
        CoverageCounter totalLine = new CoverageCounter(CoverageMetric.LINE, 0L, 0L);
        CoverageCounter totalBranch = new CoverageCounter(CoverageMetric.BRANCH, 0L, 0L);
        CoverageCounter totalInstruction = new CoverageCounter(CoverageMetric.INSTRUCTION, 0L, 0L);

        for (Path reportPath : reportPaths) {
            ModuleJacocoReport moduleReport = parseModuleReport(buildRoot, originalProjectRoot, reportPath);
            totalLine = CoverageCounter.add(totalLine, moduleReport.lineCounter());
            totalBranch = CoverageCounter.add(totalBranch, moduleReport.branchCounter());
            totalInstruction = CoverageCounter.add(totalInstruction, moduleReport.instructionCounter());
            sourceFiles.addAll(moduleReport.sourceFiles());
        }

        return Optional.of(new JacocoReport(totalLine, totalBranch, totalInstruction, sourceFiles));
    }

    private ModuleJacocoReport parseModuleReport(Path buildRoot, Path originalProjectRoot, Path reportPath) {
        Document document = XmlDocumentLoader.load(reportPath);
        Element root = document.getDocumentElement();
        Path moduleBuildRoot = moduleRootFromReport(reportPath);
        Path moduleRelativePath = buildRoot.relativize(moduleBuildRoot);
        Path moduleOriginalRoot = originalProjectRoot.resolve(moduleRelativePath).normalize();
        List<JacocoSourceFileCoverage> sourceFiles = parseSourceFiles(
                root,
                originalProjectRoot,
                moduleRelativePath,
                moduleOriginalRoot
        );
        return new ModuleJacocoReport(
                readCounter(root, CoverageMetric.LINE),
                readCounter(root, CoverageMetric.BRANCH),
                readCounter(root, CoverageMetric.INSTRUCTION),
                sourceFiles
        );
    }

    private List<JacocoSourceFileCoverage> parseSourceFiles(
            Element root,
            Path originalProjectRoot,
            Path moduleRelativePath,
            Path moduleOriginalRoot
    ) {
        LinkedHashMap<String, SourceFileBuilder> builders = new LinkedHashMap<>();
        NodeList packageNodes = root.getElementsByTagName("package");
        for (int packageIndex = 0; packageIndex < packageNodes.getLength(); packageIndex++) {
            Node packageNode = packageNodes.item(packageIndex);
            if (!(packageNode instanceof Element packageElement)) {
                continue;
            }

            String packageName = packageElement.getAttribute("name").replace('/', '.');
            Node child = packageElement.getFirstChild();
            while (child != null) {
                if (child instanceof Element childElement && "sourcefile".equals(childElement.getTagName())) {
                    String sourceFileName = childElement.getAttribute("name");
                    Path sourceFilePath = SourcePathResolver.resolveMainSource(moduleOriginalRoot, packageName, sourceFileName);
                    SourceFileBuilder builder = new SourceFileBuilder(
                            moduleRelativePath,
                            originalRelativePath(originalProjectRoot, sourceFilePath),
                            packageName,
                            sourceFileName,
                            readCounter(childElement, CoverageMetric.LINE),
                            readCounter(childElement, CoverageMetric.BRANCH),
                            readCounter(childElement, CoverageMetric.INSTRUCTION)
                    );
                    builder.lines.addAll(parseLines(childElement));
                    builders.put(sourceKey(packageName, sourceFileName), builder);
                }
                child = child.getNextSibling();
            }

            NodeList classNodes = packageElement.getElementsByTagName("class");
            for (int classIndex = 0; classIndex < classNodes.getLength(); classIndex++) {
                Node classNode = classNodes.item(classIndex);
                if (!(classNode instanceof Element classElement)) {
                    continue;
                }
                String className = classElement.getAttribute("name").replace('/', '.');
                String sourceFileName = classElement.getAttribute("sourcefilename");
                String key = sourceKey(packageName, sourceFileName);
                builders.computeIfAbsent(key, ignored -> new SourceFileBuilder(
                        moduleRelativePath,
                        originalRelativePath(
                                originalProjectRoot,
                                SourcePathResolver.resolveMainSource(moduleOriginalRoot, packageName, sourceFileName)
                        ),
                        packageName,
                        sourceFileName,
                        readCounter(classElement, CoverageMetric.LINE),
                        readCounter(classElement, CoverageMetric.BRANCH),
                        readCounter(classElement, CoverageMetric.INSTRUCTION)
                )).methods.addAll(parseMethods(classElement, className));
            }
        }

        return builders.values().stream()
                .map(SourceFileBuilder::build)
                .sorted(Comparator.comparing(sourceFile -> sourceFile.sourceFilePath().toString()))
                .toList();
    }

    private List<JacocoLineCoverage> parseLines(Element sourceFileElement) {
        List<JacocoLineCoverage> lines = new ArrayList<>();
        Node child = sourceFileElement.getFirstChild();
        while (child != null) {
            if (child instanceof Element lineElement && "line".equals(lineElement.getTagName())) {
                lines.add(new JacocoLineCoverage(
                        parseInt(lineElement.getAttribute("nr")),
                        parseInt(lineElement.getAttribute("mi")),
                        parseInt(lineElement.getAttribute("ci")),
                        parseInt(lineElement.getAttribute("mb")),
                        parseInt(lineElement.getAttribute("cb"))
                ));
            }
            child = child.getNextSibling();
        }
        return lines;
    }

    private List<JacocoMethodCoverage> parseMethods(Element classElement, String className) {
        List<JacocoMethodCoverage> methods = new ArrayList<>();
        Node child = classElement.getFirstChild();
        while (child != null) {
            if (child instanceof Element methodElement && "method".equals(methodElement.getTagName())) {
                methods.add(new JacocoMethodCoverage(
                        className,
                        methodElement.getAttribute("name"),
                        methodElement.getAttribute("desc"),
                        parseInt(methodElement.getAttribute("line")),
                        readCounter(methodElement, CoverageMetric.LINE),
                        readCounter(methodElement, CoverageMetric.BRANCH),
                        readCounter(methodElement, CoverageMetric.INSTRUCTION)
                ));
            }
            child = child.getNextSibling();
        }
        return methods;
    }

    private CoverageCounter readCounter(Element parent, CoverageMetric metric) {
        Node child = parent.getFirstChild();
        while (child != null) {
            if (child instanceof Element counterElement
                    && "counter".equals(counterElement.getTagName())
                    && metric.name().equals(counterElement.getAttribute("type"))) {
                long missed = parseLong(counterElement.getAttribute("missed"));
                long covered = parseLong(counterElement.getAttribute("covered"));
                return new CoverageCounter(metric, missed, covered);
            }
            child = child.getNextSibling();
        }
        return new CoverageCounter(metric, 0L, 0L);
    }

    private static long parseLong(String value) {
        return value == null || value.isBlank() ? 0L : Long.parseLong(value);
    }

    private static int parseInt(String value) {
        return value == null || value.isBlank() ? -1 : Integer.parseInt(value);
    }

    private List<Path> locateReports(Path buildRoot) {
        if (!Files.exists(buildRoot)) {
            return List.of();
        }

        try (Stream<Path> pathStream = Files.walk(buildRoot)) {
            return pathStream
                    .filter(path -> path.getFileName() != null && "jacoco.xml".equals(path.getFileName().toString()))
                    .filter(this::isModuleJacocoReport)
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to locate JaCoCo reports under " + buildRoot, exception);
        }
    }

    private boolean isModuleJacocoReport(Path reportPath) {
        Path parent = reportPath.getParent();
        return parent != null
                && parent.getFileName() != null
                && "jacoco".equals(parent.getFileName().toString())
                && parent.getParent() != null
                && parent.getParent().getFileName() != null
                && "site".equals(parent.getParent().getFileName().toString());
    }

    private Path moduleRootFromReport(Path reportPath) {
        return reportPath.getParent()
                .getParent()
                .getParent()
                .getParent();
    }

    private static String sourceKey(String packageName, String sourceFileName) {
        return packageName + "::" + sourceFileName;
    }

    private static Path originalRelativePath(Path originalProjectRoot, Path sourcePath) {
        return sourcePath.startsWith(originalProjectRoot)
                ? originalProjectRoot.relativize(sourcePath)
                : sourcePath;
    }

    private record ModuleJacocoReport(
            CoverageCounter lineCounter,
            CoverageCounter branchCounter,
            CoverageCounter instructionCounter,
            List<JacocoSourceFileCoverage> sourceFiles
    ) {
    }

    private static final class SourceFileBuilder {
        private final Path modulePath;
        private final Path sourceFilePath;
        private final String packageName;
        private final String sourceFileName;
        private final CoverageCounter lineCounter;
        private final CoverageCounter branchCounter;
        private final CoverageCounter instructionCounter;
        private final List<JacocoLineCoverage> lines = new ArrayList<>();
        private final List<JacocoMethodCoverage> methods = new ArrayList<>();

        private SourceFileBuilder(
                Path modulePath,
                Path sourceFilePath,
                String packageName,
                String sourceFileName,
                CoverageCounter lineCounter,
                CoverageCounter branchCounter,
                CoverageCounter instructionCounter
        ) {
            this.modulePath = modulePath;
            this.sourceFilePath = sourceFilePath;
            this.packageName = packageName;
            this.sourceFileName = sourceFileName;
            this.lineCounter = lineCounter;
            this.branchCounter = branchCounter;
            this.instructionCounter = instructionCounter;
        }

        private JacocoSourceFileCoverage build() {
            return new JacocoSourceFileCoverage(
                    modulePath,
                    sourceFilePath,
                    packageName,
                    sourceFileName,
                    lineCounter,
                    branchCounter,
                    instructionCounter,
                    List.copyOf(lines),
                    methods.stream().sorted(Comparator.comparingInt(JacocoMethodCoverage::startLine)).toList()
            );
        }
    }
}
