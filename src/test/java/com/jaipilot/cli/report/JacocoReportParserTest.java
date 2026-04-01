package com.jaipilot.cli.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jaipilot.cli.report.model.CoverageMetric;
import com.jaipilot.cli.report.model.JacocoReport;
import com.jaipilot.cli.report.model.JacocoSourceFileCoverage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JacocoReportParserTest {

    private final JacocoReportParser parser = new JacocoReportParser();

    @TempDir
    Path tempDir;

    @Test
    void parsesSourceFilesLinesAndMethods() throws IOException {
        Path reportPath = tempDir.resolve("target/site/jacoco/jacoco.xml");
        Files.createDirectories(reportPath.getParent());
        Files.copy(resourcePath("jacoco.xml"), reportPath);

        Optional<JacocoReport> report = parser.parse(tempDir, tempDir);

        assertTrue(report.isPresent());
        assertEquals(66.6667d, report.get().counter(CoverageMetric.LINE).percentage(), 0.001d);
        assertEquals(50.0d, report.get().counter(CoverageMetric.BRANCH).percentage(), 0.001d);
        assertEquals(69.5652d, report.get().counter(CoverageMetric.INSTRUCTION).percentage(), 0.001d);
        assertEquals(1, report.get().moduleCount());
        assertEquals(2, report.get().sourceFileCount());
        assertEquals(2, report.get().classCount());
        assertEquals(3, report.get().methodCount());

        JacocoSourceFileCoverage orderService = report.get().sourceFiles().stream()
                .filter(sourceFile -> sourceFile.sourceFileName().equals("OrderService.java"))
                .findFirst()
                .orElseThrow();
        assertEquals(Path.of("src/main/java/com/example/service/OrderService.java"), orderService.sourceFilePath());
        assertEquals("submit", orderService.lowestCoverageMethod(CoverageMetric.LINE).displayName());
        assertEquals(2, orderService.uncoveredLines(10).size());
        assertEquals(13, orderService.uncoveredLines(10).get(0));
        assertEquals(13, orderService.branchDeficitLines(10).get(0));
    }

    @Test
    void aggregatesReportsAcrossModules() throws IOException {
        copyJacocoReport(tempDir.resolve("module-a/target/site/jacoco/jacoco.xml"));
        copyJacocoReport(tempDir.resolve("module-b/target/site/jacoco/jacoco.xml"));

        Optional<JacocoReport> report = parser.parse(tempDir, tempDir);

        assertTrue(report.isPresent());
        assertEquals(2, report.get().moduleCount());
        assertEquals(4, report.get().sourceFileCount());
        assertTrue(report.get().sourceFiles().stream()
                .anyMatch(sourceFile -> sourceFile.sourceFilePath().equals(
                        Path.of("module-a/src/main/java/com/example/service/OrderService.java"))));
    }

    @Test
    void parsesGradleJacocoReportPaths() throws IOException {
        copyJacocoReport(tempDir.resolve("module-a/build/reports/jacoco/test/jacocoTestReport.xml"));

        Optional<JacocoReport> report = parser.parse(tempDir, tempDir);

        assertTrue(report.isPresent());
        assertEquals(1, report.get().moduleCount());
        assertTrue(report.get().sourceFiles().stream()
                .anyMatch(sourceFile -> sourceFile.sourceFilePath().equals(
                        Path.of("module-a/src/main/java/com/example/service/OrderService.java"))));
    }

    private void copyJacocoReport(Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.copy(resourcePath("jacoco.xml"), target);
    }

    private static Path resourcePath(String fileName) {
        return Path.of("src", "test", "resources", "reports", fileName);
    }
}
