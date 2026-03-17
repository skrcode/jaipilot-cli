package com.jaipilot.cli.report;

import com.jaipilot.cli.report.model.PitMutation;
import com.jaipilot.cli.report.model.PitReport;
import com.jaipilot.cli.util.SourcePathResolver;
import com.jaipilot.cli.util.XmlDocumentLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public final class PitReportParser {

    public Optional<PitReport> parse(Path buildRoot, Path originalProjectRoot) {
        List<Path> reportPaths = locateReports(buildRoot);
        if (reportPaths.isEmpty()) {
            return Optional.empty();
        }

        List<PitMutation> mutations = new ArrayList<>();
        int totalMutations = 0;
        int detectedMutations = 0;
        Map<String, Integer> statusCounts = new LinkedHashMap<>();

        for (Path reportPath : reportPaths) {
            Path moduleBuildRoot = moduleRootFromReport(reportPath);
            Path moduleRelativePath = buildRoot.relativize(moduleBuildRoot);
            Path moduleOriginalRoot = originalProjectRoot.resolve(moduleRelativePath).normalize();

            Document document = XmlDocumentLoader.load(reportPath);
            NodeList nodeList = document.getDocumentElement().getElementsByTagName("mutation");
            for (int index = 0; index < nodeList.getLength(); index++) {
                Node node = nodeList.item(index);
                if (!(node instanceof Element mutationElement)) {
                    continue;
                }
                totalMutations++;
                boolean detected = Boolean.parseBoolean(mutationElement.getAttribute("detected"));
                if (detected) {
                    detectedMutations++;
                }

                String status = mutationElement.getAttribute("status").toUpperCase(Locale.ROOT);
                statusCounts.merge(status, 1, Integer::sum);
                String mutatedClass = textContent(mutationElement, "mutatedClass");
                String sourceFileName = textContent(mutationElement, "sourceFile");
                mutations.add(new PitMutation(
                        moduleRelativePath,
                        originalRelativePath(originalProjectRoot, SourcePathResolver.resolveMainSource(
                                moduleOriginalRoot,
                                packageName(mutatedClass),
                                sourceFileName
                        )),
                        mutatedClass,
                        textContent(mutationElement, "mutatedMethod"),
                        textContent(mutationElement, "methodDescription"),
                        parseInt(textContent(mutationElement, "lineNumber")),
                        textContent(mutationElement, "mutator"),
                        status,
                        textContent(mutationElement, "description"),
                        parseInt(mutationElement.getAttribute("numberOfTestsRun")),
                        detected
                ));
            }
        }

        List<PitMutation> actionableMutations = mutations.stream()
                .filter(PitMutation::actionable)
                .sorted(Comparator
                .comparingInt(PitReportParser::mutationPriority)
                .thenComparing(PitMutation::className)
                .thenComparingInt(PitMutation::lineNumber))
                .toList();

        return Optional.of(new PitReport(
                totalMutations,
                detectedMutations,
                Map.copyOf(statusCounts),
                List.copyOf(mutations),
                actionableMutations
        ));
    }

    private List<Path> locateReports(Path buildRoot) {
        if (!Files.exists(buildRoot)) {
            return List.of();
        }

        try (Stream<Path> pathStream = Files.walk(buildRoot)) {
            return pathStream
                    .filter(path -> path.getFileName() != null && "mutations.xml".equals(path.getFileName().toString()))
                    .filter(this::isPitReport)
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to locate PIT report under " + buildRoot, exception);
        }
    }

    private boolean isPitReport(Path reportPath) {
        Path parent = reportPath.getParent();
        return parent != null
                && parent.getFileName() != null
                && "pit-reports".equals(parent.getFileName().toString())
                && parent.getParent() != null
                && parent.getParent().getFileName() != null
                && "target".equals(parent.getParent().getFileName().toString());
    }

    private static int mutationPriority(PitMutation mutation) {
        return switch (mutation.status()) {
            case "NO_COVERAGE" -> 0;
            case "SURVIVED" -> 1;
            case "TIMED_OUT" -> 2;
            case "MEMORY_ERROR", "RUN_ERROR" -> 3;
            default -> 4;
        };
    }

    private static String textContent(Element parent, String tagName) {
        Node child = parent.getFirstChild();
        while (child != null) {
            if (child instanceof Element element && tagName.equals(element.getTagName())) {
                return element.getTextContent().trim();
            }
            child = child.getNextSibling();
        }
        return "";
    }

    private static int parseInt(String value) {
        return value == null || value.isBlank() ? -1 : Integer.parseInt(value);
    }

    private Path moduleRootFromReport(Path reportPath) {
        return reportPath.getParent().getParent().getParent();
    }

    private static String packageName(String className) {
        int lastDot = className.lastIndexOf('.');
        return lastDot < 0 ? "" : className.substring(0, lastDot);
    }

    private static Path originalRelativePath(Path originalProjectRoot, Path sourcePath) {
        return sourcePath.startsWith(originalProjectRoot)
                ? originalProjectRoot.relativize(sourcePath)
                : sourcePath;
    }
}
