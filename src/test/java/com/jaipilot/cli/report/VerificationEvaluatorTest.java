package com.jaipilot.cli.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jaipilot.cli.process.ExecutionResult;
import com.jaipilot.cli.report.model.CoverageMetric;
import com.jaipilot.cli.report.model.JacocoReport;
import com.jaipilot.cli.report.model.VerificationResult;
import com.jaipilot.cli.report.model.VerificationThresholds;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerificationEvaluatorTest {

    private final JacocoReportParser jacocoReportParser = new JacocoReportParser();
    private final VerificationEvaluator evaluator = new VerificationEvaluator();

    @TempDir
    Path tempDir;

    @Test
    void flagsCoverageFailuresWithDetailedFindings() throws IOException {
        copyResource("jacoco.xml", tempDir.resolve("target/site/jacoco/jacoco.xml"));

        JacocoReport jacocoReport = jacocoReportParser.parse(tempDir, tempDir).orElseThrow();

        VerificationResult result = evaluator.evaluate(
                tempDir,
                new ExecutionResult(List.of("mvn"), 0, false, ""),
                java.util.Optional.of(jacocoReport),
                new VerificationThresholds(85.0d, 70.0d, 80.0d),
                2,
                null
        );

        assertFalse(result.successful());
        assertEquals(3, result.checks().size());
        assertTrue(result.buildIssues().isEmpty());
        assertTrue(result.coverageFindings().containsKey(CoverageMetric.LINE));
        assertEquals(2, result.coverageFindings().get(CoverageMetric.LINE).size());
        assertEquals(
                List.of(13, 14),
                result.coverageFindings().get(CoverageMetric.LINE).get(0).uncoveredLines()
        );
    }

    @Test
    void highlightsMissingGradleJacocoTaskAsBuildIssue() {
        VerificationResult result = evaluator.evaluate(
                Path.of("/tmp/project"),
                new ExecutionResult(
                        List.of("gradlew", "test", "jacocoTestReport"),
                        1,
                        false,
                        "Task 'jacocoTestReport' not found in root project 'sample-project'.\n"
                ),
                java.util.Optional.empty(),
                new VerificationThresholds(80.0d, 70.0d, 80.0d),
                3,
                null
        );

        assertFalse(result.successful());
        assertEquals(
                "Gradle verification could not find a `jacocoTestReport` task.",
                result.buildIssues().get(0).reason()
        );
        assertTrue(result.buildIssues().get(0).action().contains("jacoco"));
    }

    private void copyResource(String fileName, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.copy(resourcePath(fileName), target);
    }

    private static Path resourcePath(String fileName) {
        return Path.of("src", "test", "resources", "reports", fileName);
    }
}
