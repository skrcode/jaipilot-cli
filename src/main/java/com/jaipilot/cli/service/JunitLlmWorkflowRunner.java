package com.jaipilot.cli.service;

import com.jaipilot.cli.files.ProjectFileService;
import com.jaipilot.cli.model.JunitLlmOperation;
import com.jaipilot.cli.model.JunitLlmSessionRequest;
import com.jaipilot.cli.model.JunitLlmSessionResult;
import com.jaipilot.cli.process.BuildTool;
import com.jaipilot.cli.process.ExecutionResult;
import com.jaipilot.cli.process.GradleCommandBuilder;
import com.jaipilot.cli.process.MavenCommandBuilder;
import com.jaipilot.cli.process.ProcessExecutor;
import com.jaipilot.cli.report.JacocoReportParser;
import com.jaipilot.cli.report.model.CoverageMetric;
import com.jaipilot.cli.report.model.JacocoMethodCoverage;
import com.jaipilot.cli.report.model.JacocoReport;
import com.jaipilot.cli.report.model.JacocoSourceFileCoverage;
import com.jaipilot.cli.util.PercentFormatter;
import com.jaipilot.cli.util.SensitiveDataRedactor;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

public final class JunitLlmWorkflowRunner {

    private static final PrintWriter QUIET_WRITER = new PrintWriter(Writer.nullWriter());
    private static final String DEFAULT_JACOCO_VERSION = "0.8.13";
    private static final int COVERAGE_LINE_LIMIT = 8;

    private static final String GRADLE_COVERAGE_INIT_SCRIPT = """
            allprojects {
                plugins.withId("java") {
                    apply plugin: "jacoco"
                    tasks.named("jacocoTestReport") {
                        reports {
                            xml.required = true
                            html.required = false
                            csv.required = false
                        }
                    }
                }
            }
            """;

    private final JunitLlmSessionRunner sessionRunner;
    private final MavenCommandBuilder mavenCommandBuilder;
    private final GradleCommandBuilder gradleCommandBuilder;
    private final ProcessExecutor processExecutor;
    private final ProjectFileService fileService;
    private final JacocoReportParser jacocoReportParser;

    public JunitLlmWorkflowRunner(
            JunitLlmSessionRunner sessionRunner,
            MavenCommandBuilder mavenCommandBuilder,
            GradleCommandBuilder gradleCommandBuilder,
            ProcessExecutor processExecutor,
            ProjectFileService fileService
    ) {
        this.sessionRunner = sessionRunner;
        this.mavenCommandBuilder = mavenCommandBuilder;
        this.gradleCommandBuilder = gradleCommandBuilder;
        this.processExecutor = processExecutor;
        this.fileService = fileService;
        this.jacocoReportParser = new JacocoReportParser();
    }

    public JunitLlmSessionResult run(
            JunitLlmSessionRequest initialRequest,
            Path buildExecutable,
            List<String> additionalBuildArgs,
            Duration timeout,
            int maxFixAttempts,
            double coverageThreshold
    ) throws Exception {
        BuildTool buildTool = resolveBuildTool(initialRequest.projectRoot(), buildExecutable);
        ValidationFailure preflightFailure = validateProjectBeforeStarting(
                buildTool,
                initialRequest.projectRoot(),
                initialRequest.outputPath(),
                buildExecutable,
                additionalBuildArgs,
                timeout
        );
        if (preflightFailure != null) {
            throw new IllegalStateException(preflightFailureMessage(preflightFailure));
        }

        Path coverageCutPath = resolveCoverageCutPath(initialRequest);
        if (initialRequest.operation() == JunitLlmOperation.FIX) {
            return runFixWorkflow(
                    initialRequest,
                    buildTool,
                    buildExecutable,
                    additionalBuildArgs,
                    timeout,
                    maxFixAttempts,
                    coverageCutPath,
                    coverageThreshold
            );
        }
        return runGenerateWorkflow(
                initialRequest,
                buildTool,
                buildExecutable,
                additionalBuildArgs,
                timeout,
                maxFixAttempts,
                coverageCutPath,
                coverageThreshold
        );
    }

    private JunitLlmSessionResult runGenerateWorkflow(
            JunitLlmSessionRequest initialRequest,
            BuildTool buildTool,
            Path buildExecutable,
            List<String> additionalBuildArgs,
            Duration timeout,
            int maxFixAttempts,
            Path coverageCutPath,
            double coverageThreshold
    ) throws Exception {
        JunitLlmSessionResult currentResult = sessionRunner.run(initialRequest);
        return continueFixLoop(
                initialRequest,
                currentResult,
                buildTool,
                buildExecutable,
                additionalBuildArgs,
                timeout,
                maxFixAttempts,
                0,
                coverageCutPath,
                coverageThreshold
        );
    }

    private JunitLlmSessionResult runFixWorkflow(
            JunitLlmSessionRequest initialRequest,
            BuildTool buildTool,
            Path buildExecutable,
            List<String> additionalBuildArgs,
            Duration timeout,
            int maxFixAttempts,
            Path coverageCutPath,
            double coverageThreshold
    ) throws Exception {
        ValidationFailure initialFailure = validateLocalBuild(
                buildTool,
                initialRequest.projectRoot(),
                initialRequest.outputPath(),
                coverageCutPath,
                buildExecutable,
                additionalBuildArgs,
                timeout,
                coverageThreshold
        );
        if (initialFailure == null) {
            return new JunitLlmSessionResult(null, initialRequest.outputPath(), List.of());
        }

        JunitLlmSessionRequest fixRequest = new JunitLlmSessionRequest(
                initialRequest.projectRoot(),
                initialRequest.cutPath(),
                initialRequest.outputPath(),
                JunitLlmOperation.FIX,
                initialRequest.sessionId(),
                initialRequest.initialTestClassCode(),
                "",
                formatClientLogs(initialFailure)
        );

        JunitLlmSessionResult currentResult = sessionRunner.run(fixRequest);
        return continueFixLoop(
                initialRequest,
                currentResult,
                buildTool,
                buildExecutable,
                additionalBuildArgs,
                timeout,
                maxFixAttempts,
                1,
                coverageCutPath,
                coverageThreshold
        );
    }

    private JunitLlmSessionResult continueFixLoop(
            JunitLlmSessionRequest initialRequest,
            JunitLlmSessionResult currentResult,
            BuildTool buildTool,
            Path buildExecutable,
            List<String> additionalBuildArgs,
            Duration timeout,
            int maxFixAttempts,
            int fixAttemptsUsed,
            Path coverageCutPath,
            double coverageThreshold
    ) throws Exception {
        String currentSessionId = currentResult.sessionId();

        for (int fixAttempt = fixAttemptsUsed; ; fixAttempt++) {
            ValidationFailure validationFailure = validateLocalBuild(
                    buildTool,
                    initialRequest.projectRoot(),
                    currentResult.outputPath(),
                    coverageCutPath,
                    buildExecutable,
                    additionalBuildArgs,
                    timeout,
                    coverageThreshold
            );
            if (validationFailure == null) {
                return currentResult;
            }

            if (fixAttempt >= maxFixAttempts) {
                throw new IllegalStateException(
                        "Generated test did not pass after " + maxFixAttempts
                                + " fix attempts. Last failed phase: " + validationFailure.phase() + "."
                );
            }

            String currentTestCode = fileService.readFile(currentResult.outputPath());
            JunitLlmSessionRequest fixRequest = new JunitLlmSessionRequest(
                    initialRequest.projectRoot(),
                    initialRequest.cutPath(),
                    currentResult.outputPath(),
                    JunitLlmOperation.FIX,
                    currentSessionId,
                    currentTestCode,
                    "",
                    formatClientLogs(validationFailure)
            );

            currentResult = sessionRunner.run(fixRequest);
            currentSessionId = currentResult.sessionId();
        }
    }

    private ValidationFailure validateProjectBeforeStarting(
            BuildTool buildTool,
            Path projectRoot,
            Path outputPath,
            Path buildExecutable,
            List<String> additionalBuildArgs,
            Duration timeout
    ) throws Exception {
        return withTargetFileExcluded(outputPath, () -> validateCompilationOnly(
                buildTool,
                projectRoot,
                outputPath,
                buildExecutable,
                additionalBuildArgs,
                timeout
        ));
    }

    private ValidationFailure validateCompilationOnly(
            BuildTool buildTool,
            Path projectRoot,
            Path outputPath,
            Path buildExecutable,
            List<String> additionalBuildArgs,
            Duration timeout
    ) throws Exception {
        ExecutionResult compileResult = processExecutor.execute(
                buildTestCompileCommand(buildTool, projectRoot, outputPath, buildExecutable, additionalBuildArgs),
                projectRoot,
                timeout,
                false,
                QUIET_WRITER
        );
        if (!isSuccessful(compileResult)) {
            return new ValidationFailure(
                    "preflight-test-compile",
                    compileResult,
                    "JAIPilot excluded the target test file before running this compile. The remaining failure is unrelated to the generated/fixed test."
            );
        }
        return null;
    }

    private ValidationFailure validateLocalBuild(
            BuildTool buildTool,
            Path projectRoot,
            Path outputPath,
            Path coverageCutPath,
            Path buildExecutable,
            List<String> additionalBuildArgs,
            Duration timeout,
            double coverageThreshold
    ) throws Exception {
        String testSelector = fileService.deriveTestSelector(outputPath);
        String gradleProjectPath = gradleProjectPath(buildTool, projectRoot, outputPath);

        ExecutionResult compileResult = processExecutor.execute(
                buildTestCompileCommand(buildTool, projectRoot, outputPath, buildExecutable, additionalBuildArgs),
                projectRoot,
                timeout,
                false,
                QUIET_WRITER
        );
        if (!isSuccessful(compileResult)) {
            return new ValidationFailure("test-compile", compileResult, null);
        }

        ExecutionResult testResult = processExecutor.execute(
                buildSingleTestExecutionCommand(
                        buildTool,
                        projectRoot,
                        buildExecutable,
                        additionalBuildArgs,
                        testSelector,
                        gradleProjectPath
                ),
                projectRoot,
                timeout,
                false,
                QUIET_WRITER
        );
        if (!isSuccessful(testResult)) {
            return new ValidationFailure("test", testResult, null);
        }

        if (coverageThreshold <= 0.0d || coverageCutPath == null) {
            return null;
        }

        return validateCoverage(
                buildTool,
                projectRoot,
                coverageCutPath,
                buildExecutable,
                additionalBuildArgs,
                timeout,
                testSelector,
                coverageThreshold
        );
    }

    private ValidationFailure validateCoverage(
            BuildTool buildTool,
            Path projectRoot,
            Path coverageCutPath,
            Path buildExecutable,
            List<String> additionalBuildArgs,
            Duration timeout,
            String testSelector,
            double coverageThreshold
    ) throws Exception {
        deleteExistingCoverageReports(projectRoot);
        ExecutionResult coverageResult = runCoverageCommand(
                buildTool,
                projectRoot,
                buildExecutable,
                additionalBuildArgs,
                timeout,
                testSelector,
                gradleProjectPath(buildTool, projectRoot, coverageCutPath)
        );
        if (!isSuccessful(coverageResult)) {
            return new ValidationFailure(
                    "coverage",
                    coverageResult,
                    "JaCoCo coverage collection failed for the targeted test run."
            );
        }

        Optional<JacocoReport> jacocoReport = jacocoReportParser.parse(projectRoot, projectRoot);
        if (jacocoReport.isEmpty()) {
            return new ValidationFailure(
                    "coverage",
                    coverageResult,
                    "JaCoCo did not produce an XML report for the targeted test run."
            );
        }

        Optional<JacocoSourceFileCoverage> cutCoverage = findCoverageForCut(projectRoot, coverageCutPath, jacocoReport.get());
        if (cutCoverage.isEmpty()) {
            return new ValidationFailure(
                    "coverage",
                    coverageResult,
                    "JaCoCo did not include the class under test in its XML report: "
                            + normalizePath(projectRoot, coverageCutPath)
            );
        }

        double actualCoverage = cutCoverage.get().lineCounter().percentage();
        if (actualCoverage >= coverageThreshold) {
            return null;
        }

        return new ValidationFailure(
                "coverage",
                coverageResult,
                formatCoverageDetails(projectRoot, coverageCutPath, cutCoverage.get(), actualCoverage, coverageThreshold)
        );
    }

    private ExecutionResult runCoverageCommand(
            BuildTool buildTool,
            Path projectRoot,
            Path buildExecutable,
            List<String> additionalBuildArgs,
            Duration timeout,
            String testSelector,
            String gradleProjectPath
    ) throws Exception {
        return switch (buildTool) {
            case MAVEN -> processExecutor.execute(
                    mavenCommandBuilder.buildSingleTestCoverage(
                            projectRoot,
                            buildExecutable,
                            additionalBuildArgs,
                            testSelector,
                            DEFAULT_JACOCO_VERSION
                    ),
                    projectRoot,
                    timeout,
                    false,
                    QUIET_WRITER
            );
            case GRADLE -> runGradleCoverageCommand(
                    projectRoot,
                    buildExecutable,
                    additionalBuildArgs,
                    timeout,
                    testSelector,
                    gradleProjectPath
            );
        };
    }

    private ExecutionResult runGradleCoverageCommand(
            Path projectRoot,
            Path buildExecutable,
            List<String> additionalBuildArgs,
            Duration timeout,
            String testSelector,
            String gradleProjectPath
    ) throws Exception {
        Path initScript = Files.createTempFile("jaipilot-gradle-jacoco-", ".gradle");
        try {
            Files.writeString(initScript, GRADLE_COVERAGE_INIT_SCRIPT, StandardCharsets.UTF_8);
            return processExecutor.execute(
                    gradleCommandBuilder.buildSingleTestCoverage(
                            projectRoot,
                            buildExecutable,
                            additionalBuildArgs,
                            testSelector,
                            initScript,
                            gradleProjectPath
                    ),
                    projectRoot,
                    timeout,
                    false,
                    QUIET_WRITER
            );
        } finally {
            Files.deleteIfExists(initScript);
        }
    }

    private void deleteExistingCoverageReports(Path projectRoot) {
        try (var paths = Files.walk(projectRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> {
                        String normalized = normalizePath(path);
                        return normalized.endsWith("/target/site/jacoco/jacoco.xml")
                                || normalized.endsWith("/build/reports/jacoco/test/jacocoTestReport.xml");
                    })
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            throw new IllegalStateException("Failed to delete stale JaCoCo report " + path, exception);
                        }
                    });
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to clear stale JaCoCo reports under " + projectRoot, exception);
        }
    }

    private Optional<JacocoSourceFileCoverage> findCoverageForCut(
            Path projectRoot,
            Path coverageCutPath,
            JacocoReport jacocoReport
    ) {
        String expectedPath = normalizePath(projectRoot, coverageCutPath);
        return jacocoReport.sourceFiles().stream()
                .filter(sourceFile -> pathsMatch(sourceFile.sourceFilePath(), expectedPath))
                .findFirst();
    }

    private boolean pathsMatch(Path actualPath, String expectedPath) {
        String normalizedActual = normalizePath(actualPath);
        return normalizedActual.equals(expectedPath)
                || normalizedActual.endsWith("/" + expectedPath)
                || expectedPath.endsWith("/" + normalizedActual);
    }

    private String formatCoverageDetails(
            Path projectRoot,
            Path coverageCutPath,
            JacocoSourceFileCoverage sourceFileCoverage,
            double actualCoverage,
            double coverageThreshold
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("Coverage threshold: ").append(PercentFormatter.format(coverageThreshold)).append('%')
                .append(System.lineSeparator());
        builder.append("Actual CUT line coverage: ").append(PercentFormatter.format(actualCoverage)).append('%')
                .append(System.lineSeparator());
        builder.append("Class under test: ").append(normalizePath(projectRoot, coverageCutPath))
                .append(System.lineSeparator());

        JacocoMethodCoverage lowestMethod = sourceFileCoverage.lowestCoverageMethod(CoverageMetric.LINE);
        if (lowestMethod != null) {
            builder.append("Lowest coverage method: ").append(lowestMethod.className())
                    .append('#')
                    .append(lowestMethod.displayName())
                    .append(System.lineSeparator());
        }
        builder.append("Uncovered lines: ").append(numbers(sourceFileCoverage.uncoveredLines(COVERAGE_LINE_LIMIT)))
                .append(System.lineSeparator());
        builder.append("Branch deficit lines: ").append(numbers(sourceFileCoverage.branchDeficitLines(COVERAGE_LINE_LIMIT)))
                .append(System.lineSeparator());
        builder.append("Action: Add assertions and exercise the uncovered CUT branches until the threshold is met.");
        return builder.toString();
    }

    private String preflightFailureMessage(ValidationFailure failure) {
        return "Local build failed before JAIPilot started. The target test file was temporarily excluded, so this failure comes from other project sources. "
                + "Resolve the unrelated compile errors first. Output tail: "
                + tail(SensitiveDataRedactor.redact(failure.result().output()), 6);
    }

    private Path resolveCoverageCutPath(JunitLlmSessionRequest request) {
        Path cutPath = request.cutPath();
        if (cutPath != null && normalizePath(cutPath).contains("/src/main/java/")) {
            return cutPath.normalize();
        }
        Path inferredCutPath = fileService.inferCutPathFromTestPath(request.projectRoot(), request.outputPath());
        return inferredCutPath == null ? null : inferredCutPath.normalize();
    }

    private <T> T withTargetFileExcluded(Path outputPath, CheckedOperation<T> operation) throws Exception {
        if (!Files.isRegularFile(outputPath)) {
            return operation.run();
        }

        Path backupPath = outputPath.resolveSibling(outputPath.getFileName() + ".jaipilot-preflight-backup");
        Files.move(outputPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
        try {
            return operation.run();
        } finally {
            Files.move(backupPath, outputPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private BuildTool resolveBuildTool(Path projectRoot, Path explicitBuildExecutable) {
        return fileService.detectBuildTool(projectRoot, explicitBuildExecutable)
                .orElseThrow(() -> new IllegalStateException(
                        "Unable to detect a supported build tool for " + projectRoot
                                + ". Expected pom.xml, build.gradle(.kts), or settings.gradle(.kts)."
                ));
    }

    private List<String> buildTestCompileCommand(
            BuildTool buildTool,
            Path projectRoot,
            Path outputPath,
            Path buildExecutable,
            List<String> additionalBuildArgs
    ) {
        return switch (buildTool) {
            case MAVEN -> mavenCommandBuilder.buildTestCompile(projectRoot, buildExecutable, additionalBuildArgs);
            case GRADLE -> gradleCommandBuilder.buildTestCompile(
                    projectRoot,
                    buildExecutable,
                    additionalBuildArgs,
                    gradleProjectPath(buildTool, projectRoot, outputPath)
            );
        };
    }

    private List<String> buildSingleTestExecutionCommand(
            BuildTool buildTool,
            Path projectRoot,
            Path buildExecutable,
            List<String> additionalBuildArgs,
            String testSelector,
            String gradleProjectPath
    ) {
        return switch (buildTool) {
            case MAVEN -> mavenCommandBuilder.buildSingleTestExecution(
                    projectRoot,
                    buildExecutable,
                    additionalBuildArgs,
                    testSelector
            );
            case GRADLE -> gradleCommandBuilder.buildSingleTestExecution(
                    projectRoot,
                    buildExecutable,
                    additionalBuildArgs,
                    testSelector,
                    gradleProjectPath
            );
        };
    }

    private String gradleProjectPath(BuildTool buildTool, Path projectRoot, Path sourcePath) {
        if (buildTool != BuildTool.GRADLE) {
            return "";
        }
        return fileService.deriveGradleProjectPath(projectRoot, sourcePath);
    }

    private boolean isSuccessful(ExecutionResult result) {
        return !result.timedOut() && result.exitCode() == 0;
    }

    private String formatClientLogs(ValidationFailure failure) {
        StringBuilder builder = new StringBuilder();
        builder.append("Phase: ").append(failure.phase()).append(System.lineSeparator());
        builder.append("Command: ")
                .append(SensitiveDataRedactor.redactCommand(failure.result().command()))
                .append(System.lineSeparator());
        builder.append("Exit code: ").append(failure.result().exitCode()).append(System.lineSeparator());
        builder.append("Timed out: ").append(failure.result().timedOut()).append(System.lineSeparator());
        if (failure.details() != null && !failure.details().isBlank()) {
            builder.append("Details:").append(System.lineSeparator());
            builder.append(failure.details()).append(System.lineSeparator());
        }
        String outputExcerpt = SensitiveDataRedactor.redactBuildOutput(failure.result().output());
        builder.append("Output excerpt:").append(System.lineSeparator());
        if (outputExcerpt == null || outputExcerpt.isBlank()) {
            builder.append("no build output was captured");
        } else {
            builder.append(outputExcerpt);
        }
        return builder.toString();
    }

    private String numbers(List<Integer> numbers) {
        if (numbers == null || numbers.isEmpty()) {
            return "none";
        }
        return numbers.stream()
                .map(String::valueOf)
                .reduce((left, right) -> left + ", " + right)
                .orElse("none");
    }

    private String normalizePath(Path projectRoot, Path path) {
        Path normalizedProjectRoot = projectRoot.normalize();
        Path normalizedPath = path.normalize();
        if (normalizedPath.startsWith(normalizedProjectRoot)) {
            return normalizePath(normalizedProjectRoot.relativize(normalizedPath));
        }
        return normalizePath(normalizedPath);
    }

    private String normalizePath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private String tail(String output, int maxLines) {
        List<String> lines = output.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
        if (lines.isEmpty()) {
            return "no build output was captured";
        }
        int startIndex = Math.max(0, lines.size() - maxLines);
        return String.join(" | ", lines.subList(startIndex, lines.size()));
    }

    @FunctionalInterface
    private interface CheckedOperation<T> {
        T run() throws Exception;
    }

    private record ValidationFailure(String phase, ExecutionResult result, String details) {
    }
}
