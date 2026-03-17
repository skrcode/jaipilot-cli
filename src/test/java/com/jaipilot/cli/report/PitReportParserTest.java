package com.jaipilot.cli.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jaipilot.cli.report.model.PitReport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PitReportParserTest {

    private final PitReportParser parser = new PitReportParser();

    @TempDir
    Path tempDir;

    @Test
    void parsesMutationDetailsAndStatusBuckets() throws IOException {
        copyMutations(tempDir.resolve("target/pit-reports/mutations.xml"));

        Optional<PitReport> report = parser.parse(tempDir, tempDir);

        assertTrue(report.isPresent());
        assertEquals(5, report.get().totalMutations());
        assertEquals(40.0d, report.get().mutationScore(), 0.0001d);
        assertEquals(1, report.get().statusCount("NO_COVERAGE"));
        assertEquals("NO_COVERAGE", report.get().actionableMutations().get(0).status());
        assertEquals(
                Path.of("src/main/java/com/example/service/PaymentService.java"),
                report.get().actionableMutations().get(0).sourceFilePath()
        );
        assertEquals(0, report.get().actionableMutations().get(0).testsRun());
    }

    @Test
    void aggregatesMutationsAcrossModules() throws IOException {
        copyMutations(tempDir.resolve("module-a/target/pit-reports/mutations.xml"));
        copyMutations(tempDir.resolve("module-b/target/pit-reports/mutations.xml"));

        Optional<PitReport> report = parser.parse(tempDir, tempDir);

        assertTrue(report.isPresent());
        assertEquals(10, report.get().totalMutations());
        assertEquals(2, report.get().statusCount("NO_COVERAGE"));
        assertTrue(report.get().actionableMutations().stream()
                .anyMatch(mutation -> mutation.sourceFilePath().equals(
                        Path.of("module-a/src/main/java/com/example/service/PaymentService.java"))));
    }

    private void copyMutations(Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.copy(resourcePath("mutations.xml"), target);
    }

    private static Path resourcePath(String fileName) {
        return Path.of("src", "test", "resources", "reports", fileName);
    }
}
