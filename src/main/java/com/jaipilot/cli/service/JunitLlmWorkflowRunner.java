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
import com.jaipilot.cli.util.SensitiveDataRedactor;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.net.URI;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public final class JunitLlmWorkflowRunner {

    private static final PrintWriter QUIET_WRITER = new PrintWriter(Writer.nullWriter());
    private static final int MAX_FIX_ATTEMPTS = 20;
    private static final int MAX_COVERAGE_FIX_ATTEMPTS = 6;
    private static final double TARGET_LINE_COVERAGE = 0.80d;

    private static final String GRADLE_DEPENDENCY_SOURCES_INIT_SCRIPT = """
            gradle.rootProject {
                tasks.register("jaipilotDownloadSources") {
                    doLast {
                        Set<String> sourceNotations = new LinkedHashSet<>();
                        rootProject.allprojects.each { project ->
                            project.configurations.findAll { it.canBeResolved }.each { configuration ->
                                try {
                                    configuration.resolvedConfiguration.resolvedArtifacts.each { artifact ->
                                        String group = artifact.moduleVersion.id.group;
                                        String name = artifact.name;
                                        String version = artifact.moduleVersion.id.version;
                                        if (group != null && !group.isBlank()
                                                && name != null && !name.isBlank()
                                                && version != null && !version.isBlank()) {
                                            sourceNotations.add(group + ":" + name + ":" + version + ":sources@jar");
                                        }
                                    }
                                } catch (Exception ignored) {
                                }
                            }
                        }

                        sourceNotations.each { notation ->
                            try {
                                configurations.detachedConfiguration(dependencies.create(notation)).resolve();
                            } catch (Exception ignored) {
                            }
                        }
                        println("JAIPilot source download attempted for " + sourceNotations.size() + " dependencies.");
                    }
                }
            }
            """;

    private static final String GRADLE_JACOCO_INIT_SCRIPT = """
            import org.gradle.testing.jacoco.tasks.JacocoReport

            allprojects { project ->
                project.plugins.withId("java") {
                    project.pluginManager.apply("jacoco")
                    project.tasks.withType(JacocoReport).configureEach { task ->
                        task.reports { reports ->
                            reports.xml.required = true
                            reports.html.required = true
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
    private final boolean streamBuildLogs;
    private final PrintWriter buildLogWriter;
    private boolean dependencySourceDownloadAttempted;

    public JunitLlmWorkflowRunner(
            JunitLlmSessionRunner sessionRunner,
            MavenCommandBuilder mavenCommandBuilder,
            GradleCommandBuilder gradleCommandBuilder,
            ProcessExecutor processExecutor,
            ProjectFileService fileService
    ) {
        this(
                sessionRunner,
                mavenCommandBuilder,
                gradleCommandBuilder,
                processExecutor,
                fileService,
                false,
                QUIET_WRITER
        );
    }

    public JunitLlmWorkflowRunner(
            JunitLlmSessionRunner sessionRunner,
            MavenCommandBuilder mavenCommandBuilder,
            GradleCommandBuilder gradleCommandBuilder,
            ProcessExecutor processExecutor,
            ProjectFileService fileService,
            boolean streamBuildLogs,
            PrintWriter buildLogWriter
    ) {
        this.sessionRunner = sessionRunner;
        this.mavenCommandBuilder = mavenCommandBuilder;
        this.gradleCommandBuilder = gradleCommandBuilder;
        this.processExecutor = processExecutor;
        this.fileService = fileService;
        this.streamBuildLogs = streamBuildLogs;
        this.buildLogWriter = buildLogWriter == null ? QUIET_WRITER : buildLogWriter;
    }

    public JunitLlmSessionResult run(
            JunitLlmSessionRequest initialRequest,
            Path buildExecutable,
            List<String> additionalBuildArgs,
            Duration timeout
    ) throws Exception {
        dependencySourceDownloadAttempted = false;
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

        boolean initialDependencySourcesDownloadSuccessful = tryDownloadDependencySources(
                buildTool,
                initialRequest.projectRoot(),
                buildExecutable,
                additionalBuildArgs,
                timeout
        );
        dependencySourceDownloadAttempted = initialDependencySourcesDownloadSuccessful;
        if (initialDependencySourcesDownloadSuccessful) {
            progress("Dependency sources were downloaded during preflight.");
        } else {
            progress("Dependency source download during preflight did not complete; on-demand retry is enabled.");
        }

        JunitLlmSessionResult latestSessionResult = runSessionWithDependencySourceRetry(
                initialRequest,
                buildTool,
                buildExecutable,
                additionalBuildArgs,
                timeout
        );

        ValidationPassResult validationResult = fixUntilValidationPasses(
                initialRequest,
                latestSessionResult,
                buildTool,
                buildExecutable,
                additionalBuildArgs,
                timeout,
                normalizeTestCode(initialRequest.initialTestClassCode())
        );
        return maximizeCoverage(
                initialRequest,
                validationResult.sessionResult(),
                buildTool,
                buildExecutable,
                additionalBuildArgs,
                timeout,
                validationResult.lastBuiltTestCode(),
                validationResult.buildExecuted()
        );
    }

    private ValidationFailure validateProjectBeforeStarting(
            BuildTool buildTool,
            Path projectRoot,
            Path outputPath,
            Path buildExecutable,
            List<String> additionalBuildArgs,
            Duration timeout
    ) throws Exception {
        return withTargetFileExcluded(outputPath, () -> validatePreflightBuild(
                buildTool,
                projectRoot,
                outputPath,
                buildExecutable,
                additionalBuildArgs,
                timeout
        ));
    }

    private ValidationFailure validatePreflightBuild(
            BuildTool buildTool,
            Path projectRoot,
            Path outputPath,
            Path buildExecutable,
            List<String> additionalBuildArgs,
            Duration timeout
    ) throws Exception {
        ExecutionResult compileResult = executeBuildCommand(
                buildTestCompileCommand(buildTool, projectRoot, outputPath, buildExecutable, additionalBuildArgs),
                projectRoot,
                timeout
        );
        if (!isSuccessful(compileResult)) {
            return new ValidationFailure(
                    "preflight-test-compile",
                    compileResult,
                    "JAIPilot excluded the target test file before running this compile. The remaining failure is unrelated to the generated test."
            );
        }

        ExecutionResult codebaseRulesResult = executeBuildCommand(
                buildCodebaseRulesValidationCommand(
                        buildTool,
                        projectRoot,
                        outputPath,
                        buildExecutable,
                        additionalBuildArgs
                ),
                projectRoot,
                timeout
        );
        if (!isSuccessful(codebaseRulesResult)) {
            return new ValidationFailure(
                    "preflight-codebase-rules",
                    codebaseRulesResult,
                    "JAIPilot excluded the target test file before running project codebase rules validation. "
                            + "The remaining failure is unrelated to the generated test."
            );
        }

        return null;
    }

    private ValidationFailure validateLocalBuild(
            BuildTool buildTool,
            Path projectRoot,
            Path outputPath,
            Path buildExecutable,
            List<String> additionalBuildArgs,
            Duration timeout
    ) throws Exception {
        String testSelector = fileService.deriveTestSelector(outputPath);
        String gradleProjectPath = gradleProjectPath(buildTool, projectRoot, outputPath);

        ExecutionResult compileResult = executeBuildCommand(
                buildTestCompileCommand(buildTool, projectRoot, outputPath, buildExecutable, additionalBuildArgs),
                projectRoot,
                timeout
        );
        if (!isSuccessful(compileResult)) {
            return new ValidationFailure("test-compile", compileResult, null);
        }

        ExecutionResult codebaseRulesResult = executeBuildCommand(
                buildCodebaseRulesValidationCommand(
                        buildTool,
                        projectRoot,
                        outputPath,
                        buildExecutable,
                        additionalBuildArgs
                ),
                projectRoot,
                timeout
        );
        codebaseRulesResult = tryAutoFormatGradleAndRetryCodebaseRules(
                buildTool,
                projectRoot,
                outputPath,
                buildExecutable,
                additionalBuildArgs,
                timeout,
                gradleProjectPath,
                codebaseRulesResult
        );
        if (!isSuccessful(codebaseRulesResult)) {
            return new ValidationFailure("codebase-rules", codebaseRulesResult, null);
        }

        ExecutionResult testResult = executeBuildCommand(
                buildSingleTestExecutionCommand(
                        buildTool,
                        projectRoot,
                        buildExecutable,
                        additionalBuildArgs,
                        testSelector,
                        gradleProjectPath
                ),
                projectRoot,
                timeout
        );
        if (!isSuccessful(testResult)) {
            return new ValidationFailure("test", testResult, null);
        }

        return null;
    }

    private ExecutionResult tryAutoFormatGradleAndRetryCodebaseRules(
            BuildTool buildTool,
            Path projectRoot,
            Path outputPath,
            Path buildExecutable,
            List<String> additionalBuildArgs,
            Duration timeout,
            String gradleProjectPath,
            ExecutionResult codebaseRulesResult
    ) throws Exception {
        if (buildTool != BuildTool.GRADLE || isSuccessful(codebaseRulesResult)
                || !shouldRunGradleSpotlessApply(codebaseRulesResult.output())) {
            return codebaseRulesResult;
        }

        progress("Detected Spotless formatting violations; running spotlessApply before retrying codebase rules.");
        ExecutionResult formatResult = executeBuildCommand(
                gradleCommandBuilder.buildSpotlessApply(
                        projectRoot,
                        buildExecutable,
                        additionalBuildArgs,
                        gradleProjectPath
                ),
                projectRoot,
                timeout
        );
        if (!isSuccessful(formatResult)) {
            return mergeExecutionResults(
                    formatResult,
                    codebaseRulesResult.output(),
                    "Spotless auto-format attempt failed."
            );
        }

        progress("spotlessApply completed; retrying codebase rules validation.");
        ExecutionResult retryResult = executeBuildCommand(
                buildCodebaseRulesValidationCommand(
                        buildTool,
                        projectRoot,
                        outputPath,
                        buildExecutable,
                        additionalBuildArgs
                ),
                projectRoot,
                timeout
        );
        if (isSuccessful(retryResult)) {
            return retryResult;
        }

        return mergeExecutionResults(
                retryResult,
                codebaseRulesResult.output(),
                "Codebase rules still failed after spotlessApply."
        );
    }

    private ValidationPassResult fixUntilValidationPasses(
            JunitLlmSessionRequest initialRequest,
            JunitLlmSessionResult latestSessionResult,
            BuildTool buildTool,
            Path buildExecutable,
            List<String> additionalBuildArgs,
            Duration timeout,
            String lastBuiltTestCode
    ) throws Exception {
        String currentTestCode = readTestCode(latestSessionResult.outputPath());
        String latestBuiltTestCode = normalizeTestCode(lastBuiltTestCode);
        boolean buildExecuted = false;

        ValidationFailure validationFailure = null;
        if (hasTestCodeChanged(latestBuiltTestCode, currentTestCode)) {
            validationFailure = validateLocalBuild(
                    buildTool,
                    initialRequest.projectRoot(),
                    latestSessionResult.outputPath(),
                    buildExecutable,
                    additionalBuildArgs,
                    timeout
            );
            latestBuiltTestCode = currentTestCode;
            buildExecuted = true;
        } else {
            progress("Skipping validation build because test file is unchanged.");
            return new ValidationPassResult(latestSessionResult, latestBuiltTestCode, false);
        }

        if (validationFailure == null) {
            return new ValidationPassResult(latestSessionResult, latestBuiltTestCode, buildExecuted);
        }

        int fixAttempt = 0;
        while (validationFailure != null && fixAttempt < MAX_FIX_ATTEMPTS) {
            fixAttempt++;
            String latestTestCode = fileService.readFile(latestSessionResult.outputPath());
            JunitLlmSessionRequest fixRequest = new JunitLlmSessionRequest(
                    initialRequest.projectRoot(),
                    initialRequest.cutPath(),
                    latestSessionResult.outputPath(),
                    JunitLlmOperation.FIX,
                    latestSessionResult.sessionId(),
                    latestTestCode,
                    latestTestCode,
                    buildFixClientLogs(validationFailure)
            );
            latestSessionResult = runSessionWithDependencySourceRetry(
                    fixRequest,
                    buildTool,
                    buildExecutable,
                    additionalBuildArgs,
                    timeout
            );
            currentTestCode = readTestCode(latestSessionResult.outputPath());
            if (!hasTestCodeChanged(latestBuiltTestCode, currentTestCode)) {
                progress("Skipping validation build after fix attempt " + fixAttempt + " because test file is unchanged.");
                continue;
            }
            validationFailure = validateLocalBuild(
                    buildTool,
                    initialRequest.projectRoot(),
                    latestSessionResult.outputPath(),
                    buildExecutable,
                    additionalBuildArgs,
                    timeout
            );
            latestBuiltTestCode = currentTestCode;
            buildExecuted = true;
        }

        if (validationFailure != null) {
            throw new IllegalStateException(localValidationFailureMessage(validationFailure));
        }
        return new ValidationPassResult(latestSessionResult, latestBuiltTestCode, buildExecuted);
    }

    private JunitLlmSessionResult maximizeCoverage(
            JunitLlmSessionRequest initialRequest,
            JunitLlmSessionResult latestSessionResult,
            BuildTool buildTool,
            Path buildExecutable,
            List<String> additionalBuildArgs,
            Duration timeout,
            String lastBuiltTestCode,
            boolean validationBuildExecuted
    ) throws Exception {
        if (!validationBuildExecuted) {
            progress("Skipping coverage build because test file is unchanged.");
            return latestSessionResult;
        }

        progress("Maximizing coverage...");
        progress("Coverage target: " + percentage(TARGET_LINE_COVERAGE) + " line coverage.");

        CoverageSnapshot baselineCoverage = collectCoverage(
                buildTool,
                initialRequest.projectRoot(),
                initialRequest.cutPath(),
                latestSessionResult.outputPath(),
                buildExecutable,
                additionalBuildArgs,
                timeout
        );
        progress("Coverage before maximizing: " + baselineCoverage.describe());
        if (baselineCoverage.lineCoverage() >= TARGET_LINE_COVERAGE) {
            progress("Coverage after maximizing: " + baselineCoverage.describe());
            return latestSessionResult;
        }

        CoverageSnapshot currentCoverage = baselineCoverage;
        for (int coverageAttempt = 1; coverageAttempt <= MAX_COVERAGE_FIX_ATTEMPTS; coverageAttempt++) {
            progress("Coverage fix attempt " + coverageAttempt + "/" + MAX_COVERAGE_FIX_ATTEMPTS + "...");
            String latestTestCode = fileService.readFile(latestSessionResult.outputPath());
            JunitLlmSessionRequest coverageFixRequest = new JunitLlmSessionRequest(
                    initialRequest.projectRoot(),
                    initialRequest.cutPath(),
                    latestSessionResult.outputPath(),
                    JunitLlmOperation.FIX,
                    latestSessionResult.sessionId(),
                    latestTestCode,
                    latestTestCode,
                    buildCoverageClientLogs(initialRequest.cutPath(), currentCoverage)
            );
            latestSessionResult = runSessionWithDependencySourceRetry(
                    coverageFixRequest,
                    buildTool,
                    buildExecutable,
                    additionalBuildArgs,
                    timeout
            );

            ValidationPassResult coverageValidationResult = fixUntilValidationPasses(
                    initialRequest,
                    latestSessionResult,
                    buildTool,
                    buildExecutable,
                    additionalBuildArgs,
                    timeout,
                    lastBuiltTestCode
            );
            latestSessionResult = coverageValidationResult.sessionResult();
            lastBuiltTestCode = coverageValidationResult.lastBuiltTestCode();
            if (!coverageValidationResult.buildExecuted()) {
                progress("Coverage fix attempt " + coverageAttempt + " produced no test changes; skipping coverage build.");
                continue;
            }

            currentCoverage = collectCoverage(
                    buildTool,
                    initialRequest.projectRoot(),
                    initialRequest.cutPath(),
                    latestSessionResult.outputPath(),
                    buildExecutable,
                    additionalBuildArgs,
                    timeout
            );
            progress("Coverage after attempt " + coverageAttempt + ": " + currentCoverage.describe());
            if (currentCoverage.lineCoverage() >= TARGET_LINE_COVERAGE) {
                progress("Coverage after maximizing: " + currentCoverage.describe());
                return latestSessionResult;
            }
        }

        throw new IllegalStateException(
                "Coverage target not met. Started at " + baselineCoverage.describe()
                        + ", ended at " + currentCoverage.describe()
                        + ", target is " + percentage(TARGET_LINE_COVERAGE) + " line coverage."
        );
    }

    private String preflightFailureMessage(ValidationFailure failure) {
        return "Local build failed before JAIPilot started. Failed phase: "
                + failure.phase()
                + ". The target test file was temporarily excluded, so this failure comes from other project sources. "
                + "Resolve the unrelated build failures first. Failure details: "
                + failureDetails(failure.result().output(), 6);
    }

    private String localValidationFailureMessage(ValidationFailure failure) {
        return "Generated/fixed test did not pass local validation. Failed phase: "
                + failure.phase()
                + ". Failure details: "
                + failureDetails(failure.result().output(), 6);
    }

    private String buildFixClientLogs(ValidationFailure failure) {
        StringBuilder builder = new StringBuilder();
        builder.append("Failed phase: ")
                .append(failure.phase())
                .append(System.lineSeparator());
        if (failure.details() != null && !failure.details().isBlank()) {
            builder.append(failure.details()).append(System.lineSeparator());
        }
        builder.append("Build failure details:")
                .append(System.lineSeparator())
                .append(failureDetails(failure.result().output(), 20));
        return builder.toString();
    }

    private String buildCoverageClientLogs(Path cutPath, CoverageSnapshot snapshot) {
        StringBuilder builder = new StringBuilder();
        builder.append("Coverage target: ")
                .append(percentage(TARGET_LINE_COVERAGE))
                .append(" line coverage.")
                .append(System.lineSeparator());
        builder.append("Class under test: ")
                .append(cutPath)
                .append(System.lineSeparator());
        builder.append("Current line coverage: ")
                .append(snapshot.describe())
                .append(System.lineSeparator());
        builder.append("Improve branch and edge-case assertions to raise coverage without breaking existing behavior.");
        return builder.toString();
    }

    private CoverageSnapshot collectCoverage(
            BuildTool buildTool,
            Path projectRoot,
            Path cutPath,
            Path outputPath,
            Path buildExecutable,
            List<String> additionalBuildArgs,
            Duration timeout
    ) throws Exception {
        String testSelector = fileService.deriveTestSelector(outputPath);
        String gradleProjectPath = gradleProjectPath(buildTool, projectRoot, outputPath);
        Path reportPath = jacocoReportPath(buildTool, projectRoot, gradleProjectPath);
        CoverageCoordinate coverageCoordinate = coverageCoordinate(cutPath);

        ExecutionResult coverageResult = executeCoverageCommand(
                buildTool,
                projectRoot,
                buildExecutable,
                additionalBuildArgs,
                timeout,
                testSelector,
                gradleProjectPath
        );
        if (!isSuccessful(coverageResult)) {
            throw new IllegalStateException(
                    "Coverage collection failed. Failure details: "
                            + failureDetails(coverageResult.output(), 8)
            );
        }

        CoverageSnapshot snapshot;
        try {
            snapshot = readJacocoLineCoverage(reportPath, coverageCoordinate);
        } catch (IllegalStateException exception) {
            if (!shouldRetryMavenCoverageWithPrepareAgent(buildTool, reportPath, coverageResult.output())) {
                throw exception;
            }
            progress("JaCoCo report missing after initial coverage run; retrying with explicit JaCoCo agent.");
            ExecutionResult retryCoverageResult = executeBuildCommand(
                    mavenCommandBuilder.buildSingleTestCoverageWithPrepareAgent(
                            projectRoot,
                            buildExecutable,
                            additionalBuildArgs,
                            testSelector
                    ),
                    projectRoot,
                    timeout
            );
            if (!isSuccessful(retryCoverageResult)) {
                throw new IllegalStateException(
                        "Coverage collection failed after explicit JaCoCo agent retry. Failure details: "
                                + failureDetails(retryCoverageResult.output(), 8)
                );
            }
            snapshot = readJacocoLineCoverage(reportPath, coverageCoordinate);
        }
        progress("JaCoCo report: " + reportPath);
        return snapshot;
    }

    private ExecutionResult executeCoverageCommand(
            BuildTool buildTool,
            Path projectRoot,
            Path buildExecutable,
            List<String> additionalBuildArgs,
            Duration timeout,
            String testSelector,
            String gradleProjectPath
    ) throws Exception {
        return switch (buildTool) {
            case MAVEN -> executeBuildCommand(
                    mavenCommandBuilder.buildSingleTestCoverage(
                            projectRoot,
                            buildExecutable,
                            additionalBuildArgs,
                            testSelector
                    ),
                    projectRoot,
                    timeout
            );
            case GRADLE -> runGradleCoverage(
                    projectRoot,
                    buildExecutable,
                    additionalBuildArgs,
                    timeout,
                    testSelector,
                    gradleProjectPath
            );
        };
    }

    private ExecutionResult runGradleCoverage(
            Path projectRoot,
            Path buildExecutable,
            List<String> additionalBuildArgs,
            Duration timeout,
            String testSelector,
            String gradleProjectPath
    ) throws Exception {
        Path initScript = Files.createTempFile("jaipilot-gradle-jacoco-", ".gradle");
        try {
            Files.writeString(initScript, GRADLE_JACOCO_INIT_SCRIPT, StandardCharsets.UTF_8);
            return executeBuildCommand(
                    gradleCommandBuilder.buildSingleTestCoverage(
                            projectRoot,
                            buildExecutable,
                            additionalBuildArgs,
                            testSelector,
                            gradleProjectPath,
                            initScript
                    ),
                    projectRoot,
                    timeout
            );
        } finally {
            Files.deleteIfExists(initScript);
        }
    }

    private CoverageSnapshot readJacocoLineCoverage(Path reportPath, CoverageCoordinate coverageCoordinate) throws Exception {
        if (!Files.isRegularFile(reportPath)) {
            throw new IllegalStateException("JaCoCo report not found at " + reportPath);
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        try (InputStream reportStream = Files.newInputStream(reportPath)) {
            NodeList packageNodes = factory.newDocumentBuilder()
                    .parse(reportStream)
                    .getElementsByTagName("package");
            for (int packageIndex = 0; packageIndex < packageNodes.getLength(); packageIndex++) {
                Node packageNode = packageNodes.item(packageIndex);
                if (!(packageNode instanceof Element packageElement)) {
                    continue;
                }
                if (!coverageCoordinate.packagePath().equals(packageElement.getAttribute("name"))) {
                    continue;
                }
                NodeList sourceFileNodes = packageElement.getElementsByTagName("sourcefile");
                for (int sourceFileIndex = 0; sourceFileIndex < sourceFileNodes.getLength(); sourceFileIndex++) {
                    Node sourceFileNode = sourceFileNodes.item(sourceFileIndex);
                    if (!(sourceFileNode instanceof Element sourceFileElement)) {
                        continue;
                    }
                    if (!coverageCoordinate.sourceFileName().equals(sourceFileElement.getAttribute("name"))) {
                        continue;
                    }
                    return lineCoverageFromCounter(reportPath, sourceFileElement);
                }
            }
        }

        throw new IllegalStateException(
                "JaCoCo report did not contain line coverage for " + coverageCoordinate.packagePath() + "/"
                        + coverageCoordinate.sourceFileName()
        );
    }

    private CoverageSnapshot lineCoverageFromCounter(Path reportPath, Element sourceFileElement) {
        NodeList counters = sourceFileElement.getElementsByTagName("counter");
        for (int index = 0; index < counters.getLength(); index++) {
            Node counterNode = counters.item(index);
            if (!(counterNode instanceof Element counterElement)) {
                continue;
            }
            if (!"LINE".equals(counterElement.getAttribute("type"))) {
                continue;
            }
            int missed = Integer.parseInt(counterElement.getAttribute("missed"));
            int covered = Integer.parseInt(counterElement.getAttribute("covered"));
            return new CoverageSnapshot(reportPath, covered, missed);
        }
        throw new IllegalStateException("JaCoCo report did not contain LINE counter for " + reportPath);
    }

    private CoverageCoordinate coverageCoordinate(Path cutPath) {
        String sourceFileName = cutPath.getFileName() == null ? "" : cutPath.getFileName().toString();
        String packagePath = "";
        for (String line : fileService.readFile(cutPath).lines().toList()) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("package ") || !trimmed.endsWith(";")) {
                continue;
            }
            String packageName = trimmed.substring("package ".length(), trimmed.length() - 1).trim();
            packagePath = packageName.replace('.', '/');
            break;
        }
        return new CoverageCoordinate(packagePath, sourceFileName);
    }

    private Path jacocoReportPath(BuildTool buildTool, Path projectRoot, String gradleProjectPath) {
        return switch (buildTool) {
            case MAVEN -> projectRoot.resolve("target/site/jacoco/jacoco.xml").normalize();
            case GRADLE -> gradleProjectDirectory(projectRoot, gradleProjectPath)
                    .resolve("build/reports/jacoco/test/jacocoTestReport.xml")
                    .normalize();
        };
    }

    private Path gradleProjectDirectory(Path projectRoot, String gradleProjectPath) {
        if (gradleProjectPath == null || gradleProjectPath.isBlank()) {
            return projectRoot.normalize();
        }
        Path directory = projectRoot.normalize();
        for (String segment : gradleProjectPath.split(":")) {
            if (!segment.isBlank()) {
                directory = directory.resolve(segment);
            }
        }
        return directory.normalize();
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

    private JunitLlmSessionResult runSessionWithDependencySourceRetry(
            JunitLlmSessionRequest request,
            BuildTool buildTool,
            Path buildExecutable,
            List<String> additionalBuildArgs,
            Duration timeout
    ) throws Exception {
        try {
            return sessionRunner.run(request);
        } catch (IllegalStateException exception) {
            if (!shouldRetryForMissingContextPath(exception) || dependencySourceDownloadAttempted) {
                throw exception;
            }
            dependencySourceDownloadAttempted = true;
            tryDownloadDependencySources(buildTool, request.projectRoot(), buildExecutable, additionalBuildArgs, timeout);
            return sessionRunner.run(request);
        }
    }

    private boolean shouldRetryForMissingContextPath(IllegalStateException exception) {
        Throwable current = exception;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("Unable to resolve requested context class path ")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean tryDownloadDependencySources(
            BuildTool buildTool,
            Path projectRoot,
            Path buildExecutable,
            List<String> additionalBuildArgs,
            Duration timeout
    ) {
        ExecutionResult result = tryDownloadDependencySourcesResult(
                buildTool,
                projectRoot,
                buildExecutable,
                additionalBuildArgs,
                timeout
        );
        return result != null && isSuccessful(result);
    }

    private ExecutionResult tryDownloadDependencySourcesResult(
            BuildTool buildTool,
            Path projectRoot,
            Path buildExecutable,
            List<String> additionalBuildArgs,
            Duration timeout
    ) {
        try {
            return switch (buildTool) {
                case MAVEN -> executeBuildCommand(
                        mavenCommandBuilder.buildDependencySourcesDownload(projectRoot, buildExecutable, additionalBuildArgs),
                        projectRoot,
                        timeout
                );
                case GRADLE -> runGradleDependencySourceDownload(
                        projectRoot,
                        buildExecutable,
                        additionalBuildArgs,
                        timeout
                );
            };
        } catch (Exception ignored) {
            // Best effort: if source download fails, the second backend/context attempt will report the original miss.
            return null;
        }
    }

    private ExecutionResult runGradleDependencySourceDownload(
            Path projectRoot,
            Path buildExecutable,
            List<String> additionalBuildArgs,
            Duration timeout
    ) throws Exception {
        Path initScript = Files.createTempFile("jaipilot-gradle-sources-", ".gradle");
        try {
            Files.writeString(initScript, GRADLE_DEPENDENCY_SOURCES_INIT_SCRIPT, StandardCharsets.UTF_8);
            return executeBuildCommand(
                    gradleCommandBuilder.buildDependencySourcesDownload(
                            projectRoot,
                            buildExecutable,
                            additionalBuildArgs,
                            initScript
                    ),
                    projectRoot,
                    timeout
            );
        } finally {
            Files.deleteIfExists(initScript);
        }
    }

    private ExecutionResult executeBuildCommand(
            List<String> command,
            Path projectRoot,
            Duration timeout
    ) throws Exception {
        ExecutionResult result = processExecutor.execute(
                command,
                projectRoot,
                timeout,
                streamBuildLogs,
                buildLogWriter
        );
        if (shouldRetryWithoutMavenWrapper(result)) {
            String systemMavenCommand = systemMavenCommand();
            if (!isCommandAvailable(systemMavenCommand)) {
                return result;
            }
            List<String> fallbackCommand = new ArrayList<>(command);
            fallbackCommand.set(0, systemMavenCommand);
            buildLogWriter.println("PROGRESS: Maven wrapper is incomplete; retrying with `" + fallbackCommand.get(0) + "`.");
            buildLogWriter.flush();
            return processExecutor.execute(
                    fallbackCommand,
                    projectRoot,
                    timeout,
                    streamBuildLogs,
                    buildLogWriter
            );
        }
        return result;
    }

    private boolean shouldRetryWithoutMavenWrapper(ExecutionResult result) {
        if (result.command().isEmpty()) {
            return false;
        }
        String executable = result.command().get(0).toLowerCase(Locale.ROOT);
        if (!(executable.endsWith("/mvnw") || executable.endsWith("\\mvnw") || executable.endsWith("mvnw")
                || executable.endsWith("/mvnw.cmd") || executable.endsWith("\\mvnw.cmd") || executable.endsWith("mvnw.cmd"))) {
            return false;
        }
        String output = result.output() == null ? "" : result.output().toLowerCase(Locale.ROOT);
        return output.contains("maven-wrapper.properties file does not exist")
                || output.contains("could not find or load main class org.apache.maven.wrapper.mavenwrappermain");
    }

    private boolean shouldRunGradleSpotlessApply(String output) {
        if (output == null || output.isBlank()) {
            return false;
        }
        String normalizedOutput = output.toLowerCase(Locale.ROOT);
        if (!normalizedOutput.contains("spotless")) {
            return false;
        }
        return normalizedOutput.contains("spotlessjavacheck")
                || normalizedOutput.contains("spotlesscheck")
                || normalizedOutput.contains("format violations")
                || normalizedOutput.contains("spotlessapply");
    }

    private String systemMavenCommand() {
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
        return windows ? "mvn.cmd" : "mvn";
    }

    private boolean isCommandAvailable(String executable) {
        if (executable == null || executable.isBlank()) {
            return false;
        }
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
        try {
            if (executable.contains("/") || executable.contains("\\")) {
                Path candidate = Path.of(executable);
                return Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate));
            }
            String path = System.getenv("PATH");
            if (path == null || path.isBlank()) {
                return false;
            }
            String[] suffixes = windows ? new String[] {"", ".cmd", ".bat", ".exe"} : new String[] {""};
            for (String directory : path.split(Pattern.quote(System.getProperty("path.separator", ":")))) {
                if (directory == null || directory.isBlank()) {
                    continue;
                }
                for (String suffix : suffixes) {
                    Path candidate = Path.of(directory, executable + suffix);
                    if (Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate))) {
                        return true;
                    }
                }
            }
            return false;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean shouldRetryMavenCoverageWithPrepareAgent(
            BuildTool buildTool,
            Path reportPath,
            String output
    ) {
        if (buildTool != BuildTool.MAVEN || Files.isRegularFile(reportPath)) {
            return false;
        }
        String normalizedOutput = output == null ? "" : output.toLowerCase(Locale.ROOT);
        return normalizedOutput.contains("skipping jacoco execution due to missing execution data file")
                || normalizedOutput.contains("missing execution data file");
    }

    private ExecutionResult mergeExecutionResults(
            ExecutionResult latestResult,
            String previousOutput,
            String contextMessage
    ) {
        StringBuilder mergedOutput = new StringBuilder();
        if (previousOutput != null && !previousOutput.isBlank()) {
            mergedOutput.append(previousOutput.strip()).append(System.lineSeparator()).append(System.lineSeparator());
        }
        mergedOutput.append(contextMessage).append(System.lineSeparator());
        if (latestResult.output() != null && !latestResult.output().isBlank()) {
            mergedOutput.append(latestResult.output().strip()).append(System.lineSeparator());
        }
        return new ExecutionResult(
                latestResult.command(),
                latestResult.exitCode(),
                latestResult.timedOut(),
                mergedOutput.toString()
        );
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

    private List<String> buildCodebaseRulesValidationCommand(
            BuildTool buildTool,
            Path projectRoot,
            Path outputPath,
            Path buildExecutable,
            List<String> additionalBuildArgs
    ) {
        return switch (buildTool) {
            case MAVEN -> mavenCommandBuilder.buildCodebaseRulesValidation(
                    projectRoot,
                    buildExecutable,
                    additionalBuildArgs
            );
            case GRADLE -> gradleCommandBuilder.buildCodebaseRulesValidation(
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

    private String readTestCode(Path outputPath) {
        if (outputPath == null || !Files.isRegularFile(outputPath)) {
            return "";
        }
        return fileService.readFile(outputPath);
    }

    private String normalizeTestCode(String testCode) {
        return testCode == null ? "" : testCode;
    }

    private boolean hasTestCodeChanged(String previousTestCode, String currentTestCode) {
        return !normalizeTestCode(previousTestCode).equals(normalizeTestCode(currentTestCode));
    }

    private String failureDetails(String output, int fallbackTailLines) {
        String enrichedOutput = enrichBuildOutputWithStaticAnalysisDetails(output);
        String summarized = SensitiveDataRedactor.redactBuildOutput(enrichedOutput);
        if (summarized != null && !summarized.isBlank()) {
            return summarized;
        }
        return tail(SensitiveDataRedactor.redact(enrichedOutput), fallbackTailLines);
    }

    private void progress(String message) {
        buildLogWriter.println("PROGRESS: " + message);
        buildLogWriter.flush();
    }

    private String percentage(double ratio) {
        return String.format(Locale.ROOT, "%.2f%%", ratio * 100.0d);
    }

    private String enrichBuildOutputWithStaticAnalysisDetails(String output) {
        if (output == null || output.isBlank()) {
            return output;
        }
        List<String> checkstyleDetails = extractCheckstyleViolationDetails(output);
        if (checkstyleDetails.isEmpty()) {
            return output;
        }
        StringBuilder builder = new StringBuilder(output.stripTrailing());
        builder.append(System.lineSeparator())
                .append("Extracted checkstyle violations:")
                .append(System.lineSeparator());
        for (String detail : checkstyleDetails) {
            builder.append(detail).append(System.lineSeparator());
        }
        return builder.toString();
    }

    private List<String> extractCheckstyleViolationDetails(String output) {
        List<Path> candidateReports = checkstyleReportCandidates(output);
        if (candidateReports.isEmpty()) {
            return List.of();
        }

        List<String> details = new ArrayList<>();
        for (Path reportPath : candidateReports) {
            readCheckstyleViolations(reportPath, details, 15);
            if (!details.isEmpty()) {
                break;
            }
        }
        return details;
    }

    private List<Path> checkstyleReportCandidates(String output) {
        List<Path> reports = new ArrayList<>();
        for (String line : output.lines().toList()) {
            String trimmed = line.trim();
            int markerIndex = trimmed.toLowerCase(Locale.ROOT).indexOf("see the report at:");
            if (markerIndex < 0) {
                continue;
            }
            String uriText = trimmed.substring(markerIndex + "see the report at:".length()).trim();
            if (uriText.isBlank() || !uriText.startsWith("file:")) {
                continue;
            }
            try {
                Path reportPath = Path.of(URI.create(uriText)).normalize();
                addCheckstyleReportCandidates(reportPath, reports);
            } catch (Exception ignored) {
                // ignore malformed URIs
            }
        }
        return reports;
    }

    private void addCheckstyleReportCandidates(Path reportPath, List<Path> reports) {
        if (reportPath == null) {
            return;
        }
        String fileName = reportPath.getFileName() == null ? "" : reportPath.getFileName().toString();
        if (fileName.endsWith(".xml")) {
            reports.add(reportPath);
            return;
        }
        if (fileName.endsWith(".html")) {
            reports.add(reportPath.resolveSibling(fileName.substring(0, fileName.length() - ".html".length()) + ".xml"));
        }
        Path parent = reportPath.getParent();
        if (parent != null) {
            reports.add(parent.resolve("test.xml"));
            reports.add(parent.resolve("main.xml"));
        }
    }

    private void readCheckstyleViolations(Path reportPath, List<String> details, int maxDetails) {
        if (reportPath == null || !Files.isRegularFile(reportPath) || details.size() >= maxDetails) {
            return;
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            try (InputStream reportStream = Files.newInputStream(reportPath)) {
                NodeList fileNodes = factory.newDocumentBuilder()
                        .parse(reportStream)
                        .getElementsByTagName("file");
                for (int fileIndex = 0; fileIndex < fileNodes.getLength() && details.size() < maxDetails; fileIndex++) {
                    Node fileNode = fileNodes.item(fileIndex);
                    if (!(fileNode instanceof Element fileElement)) {
                        continue;
                    }
                    String fileName = fileElement.getAttribute("name");
                    NodeList errorNodes = fileElement.getElementsByTagName("error");
                    for (int errorIndex = 0; errorIndex < errorNodes.getLength() && details.size() < maxDetails; errorIndex++) {
                        Node errorNode = errorNodes.item(errorIndex);
                        if (!(errorNode instanceof Element errorElement)) {
                            continue;
                        }
                        details.add(formatCheckstyleViolation(fileName, errorElement));
                    }
                }
            }
        } catch (Exception ignored) {
            // ignore parsing issues; original build output remains available
        }
    }

    private String formatCheckstyleViolation(String fileName, Element errorElement) {
        String line = errorElement.getAttribute("line");
        String column = errorElement.getAttribute("column");
        String severity = errorElement.getAttribute("severity");
        String message = errorElement.getAttribute("message");
        StringBuilder builder = new StringBuilder();
        builder.append(fileName == null || fileName.isBlank() ? "<unknown-file>" : fileName);
        if (line != null && !line.isBlank()) {
            builder.append(':').append(line);
            if (column != null && !column.isBlank()) {
                builder.append(':').append(column);
            }
        }
        if (severity != null && !severity.isBlank()) {
            builder.append(" [").append(severity).append(']');
        }
        if (message != null && !message.isBlank()) {
            builder.append(' ').append(message);
        }
        return builder.toString();
    }

    @FunctionalInterface
    private interface CheckedOperation<T> {
        T run() throws Exception;
    }

    private record CoverageCoordinate(String packagePath, String sourceFileName) {
    }

    private record CoverageSnapshot(Path reportPath, int coveredLines, int missedLines) {
        int totalLines() {
            return coveredLines + missedLines;
        }

        double lineCoverage() {
            int total = totalLines();
            if (total <= 0) {
                return 0.0d;
            }
            return coveredLines / (double) total;
        }

        String describe() {
            return String.format(
                    Locale.ROOT,
                    "%.2f%% (%d/%d lines) [%s]",
                    lineCoverage() * 100.0d,
                    coveredLines,
                    totalLines(),
                    reportPath
            );
        }
    }

    private record ValidationPassResult(
            JunitLlmSessionResult sessionResult,
            String lastBuiltTestCode,
            boolean buildExecuted
    ) {
    }

    private record ValidationFailure(String phase, ExecutionResult result, String details) {
    }
}
