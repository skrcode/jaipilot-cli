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
import java.io.ByteArrayInputStream;
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
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public final class JunitLlmWorkflowRunner {

    private static final PrintWriter QUIET_WRITER = new PrintWriter(Writer.nullWriter());
    private static final int MAX_FIX_ATTEMPTS = 20;
    private static final int MAX_COVERAGE_FIX_ATTEMPTS = 6;
    private static final double TARGET_LINE_COVERAGE = 0.80d;
    private static final List<String> MAVEN_COMPILE_SANITY_ARGS = List.of("-q");
    private static final List<String> GRADLE_COMPILE_SANITY_ARGS = List.of("--quiet");
    private static final List<String> MAVEN_VALIDATION_ARGS = List.of("-q");
    private static final List<String> GRADLE_VALIDATION_ARGS = List.of("--quiet");
    private static final Pattern DOCTYPE_DECLARATION_PATTERN = Pattern.compile(
            "(?is)<!DOCTYPE\\s+[^>]*(?:\\[[\\s\\S]*?\\])?\\s*>"
    );

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
        BuildTool buildTool = resolveBuildTool(initialRequest.projectRoot(), buildExecutable);
        Path compilationRoot = resolveCompilationRoot(
                buildTool,
                initialRequest.projectRoot(),
                initialRequest.cutPath(),
                initialRequest.outputPath()
        );

        ValidationFailure preflightFailure = validateProjectBeforeStarting(
                buildTool,
                initialRequest.projectRoot(),
                compilationRoot,
                initialRequest.cutPath(),
                initialRequest.outputPath(),
                buildExecutable,
                timeout
        );
        if (preflightFailure != null) {
            throw new IllegalStateException(preflightFailureMessage(preflightFailure));
        }

        JunitLlmSessionResult latestSessionResult = sessionRunner.run(initialRequest);

        ValidationPassResult validationResult = fixUntilValidationPasses(
                initialRequest,
                latestSessionResult,
                buildTool,
                compilationRoot,
                buildExecutable,
                timeout,
                normalizeTestCode(initialRequest.initialTestClassCode())
        );

        return maximizeCoverage(
                initialRequest,
                validationResult,
                buildTool,
                compilationRoot,
                buildExecutable,
                timeout
        );
    }

    private ValidationFailure validateProjectBeforeStarting(
            BuildTool buildTool,
            Path projectRoot,
            Path compilationRoot,
            Path requiredClassPath,
            Path outputPath,
            Path buildExecutable,
            Duration timeout
    ) throws Exception {
        return withTargetFileExcluded(outputPath, () -> validatePreflightBuild(
                buildTool,
                projectRoot,
                compilationRoot,
                requiredClassPath,
                outputPath,
                buildExecutable,
                timeout
        ));
    }

    private ValidationFailure validatePreflightBuild(
            BuildTool buildTool,
            Path projectRoot,
            Path compilationRoot,
            Path requiredClassPath,
            Path outputPath,
            Path buildExecutable,
            Duration timeout
    ) throws Exception {
        ExecutionResult compileResult = executeBuildCommand(
                buildCompilationCommand(
                        buildTool,
                        projectRoot,
                        requiredClassPath,
                        buildExecutable
                ),
                workingDirectory(buildTool, projectRoot, compilationRoot),
                timeout
        );
        if (!isSuccessful(compileResult)) {
            return new ValidationFailure(
                    "preflight-test-compile",
                    compileResult,
                    "JAIPilot excluded the target test file before running this compilation sanity check. "
                            + "The remaining failure is unrelated to the generated test."
            );
        }

        ExecutionResult codebaseRulesResult = executeBuildCommand(
                buildCodebaseRulesValidationCommand(
                        buildTool,
                        projectRoot,
                        outputPath,
                        buildExecutable
                ),
                workingDirectory(buildTool, projectRoot, compilationRoot),
                timeout
        );
        if (!isSuccessful(codebaseRulesResult)) {
            return new ValidationFailure(
                    "preflight-codebase-rules",
                    codebaseRulesResult,
                    "JAIPilot excluded the target test file before running project rule validation. "
                            + "The remaining failure is unrelated to the generated test."
            );
        }

        return null;
    }

    private ValidationFailure validateLocalBuild(
            BuildTool buildTool,
            Path projectRoot,
            Path compilationRoot,
            Path cutPath,
            Path outputPath,
            Path buildExecutable,
            Duration timeout
    ) throws Exception {
        ExecutionResult compileResult = executeBuildCommand(
                buildCompilationCommand(
                        buildTool,
                        projectRoot,
                        cutPath,
                        buildExecutable
                ),
                workingDirectory(buildTool, projectRoot, compilationRoot),
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
                        buildExecutable
                ),
                workingDirectory(buildTool, projectRoot, compilationRoot),
                timeout
        );
        if (!isSuccessful(codebaseRulesResult)) {
            return new ValidationFailure("codebase-rules", codebaseRulesResult, null);
        }

        String testSelector = fileService.deriveTestSelector(outputPath);
        ExecutionResult testResult = executeBuildCommand(
                buildSingleTestExecutionCommand(
                        buildTool,
                        projectRoot,
                        outputPath,
                        buildExecutable,
                        testSelector
                ),
                workingDirectory(buildTool, projectRoot, compilationRoot),
                timeout
        );
        if (!isSuccessful(testResult)) {
            return new ValidationFailure("test", testResult, null);
        }

        return null;
    }

    private ValidationPassResult fixUntilValidationPasses(
            JunitLlmSessionRequest initialRequest,
            JunitLlmSessionResult latestSessionResult,
            BuildTool buildTool,
            Path compilationRoot,
            Path buildExecutable,
            Duration timeout,
            String lastValidatedTestCode
    ) throws Exception {
        String currentTestCode = readTestCode(latestSessionResult.outputPath());
        String latestValidatedTestCode = normalizeTestCode(lastValidatedTestCode);
        boolean buildExecuted = true;

        ValidationFailure validationFailure = validateLocalBuild(
                buildTool,
                initialRequest.projectRoot(),
                compilationRoot,
                initialRequest.cutPath(),
                latestSessionResult.outputPath(),
                buildExecutable,
                timeout
        );
        latestValidatedTestCode = currentTestCode;

        if (validationFailure == null) {
            return new ValidationPassResult(latestSessionResult, latestValidatedTestCode, buildExecuted);
        }

        for (int fixAttempt = 1; fixAttempt <= MAX_FIX_ATTEMPTS; fixAttempt++) {
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
            latestSessionResult = sessionRunner.run(fixRequest);

            currentTestCode = readTestCode(latestSessionResult.outputPath());
            if (!hasTestCodeChanged(latestValidatedTestCode, currentTestCode)) {
                progress(
                        "Fix attempt " + fixAttempt + " did not change the test file; retrying with the same failure logs."
                );
                continue;
            }

            validationFailure = validateLocalBuild(
                    buildTool,
                    initialRequest.projectRoot(),
                    compilationRoot,
                    initialRequest.cutPath(),
                    latestSessionResult.outputPath(),
                    buildExecutable,
                    timeout
            );
            latestValidatedTestCode = currentTestCode;
            buildExecuted = true;
            if (validationFailure == null) {
                return new ValidationPassResult(latestSessionResult, latestValidatedTestCode, buildExecuted);
            }
        }

        throw new IllegalStateException(localValidationFailureMessage(validationFailure));
    }

    private JunitLlmSessionResult maximizeCoverage(
            JunitLlmSessionRequest initialRequest,
            ValidationPassResult validationResult,
            BuildTool buildTool,
            Path compilationRoot,
            Path buildExecutable,
            Duration timeout
    ) throws Exception {
        if (!validationResult.buildExecuted()) {
            progress("Skipping coverage build because test file is unchanged.");
            return validationResult.sessionResult();
        }

        progress("Maximizing coverage...");
        progress("Coverage target: " + percentage(TARGET_LINE_COVERAGE) + " line coverage.");

        JunitLlmSessionResult latestSessionResult = validationResult.sessionResult();
        String lastValidatedTestCode = validationResult.lastValidatedTestCode();

        CoverageRun baselineCoverage = collectCoverage(
                buildTool,
                initialRequest.projectRoot(),
                compilationRoot,
                initialRequest.cutPath(),
                latestSessionResult.outputPath(),
                buildExecutable,
                timeout
        );
        progress("Coverage before maximizing: " + baselineCoverage.snapshot().describe());
        if (baselineCoverage.snapshot().lineCoverage() >= TARGET_LINE_COVERAGE) {
            progress("Coverage after maximizing: " + baselineCoverage.snapshot().describe());
            return latestSessionResult;
        }

        CoverageRun currentCoverage = baselineCoverage;
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
            latestSessionResult = sessionRunner.run(coverageFixRequest);

            ValidationPassResult postCoverageValidation = fixUntilValidationPasses(
                    initialRequest,
                    latestSessionResult,
                    buildTool,
                    compilationRoot,
                    buildExecutable,
                    timeout,
                    lastValidatedTestCode
            );
            latestSessionResult = postCoverageValidation.sessionResult();
            lastValidatedTestCode = postCoverageValidation.lastValidatedTestCode();
            if (!postCoverageValidation.buildExecuted()) {
                progress("Coverage fix attempt " + coverageAttempt + " produced no test changes; skipping coverage build.");
                continue;
            }

            currentCoverage = collectCoverage(
                    buildTool,
                    initialRequest.projectRoot(),
                    compilationRoot,
                    initialRequest.cutPath(),
                    latestSessionResult.outputPath(),
                    buildExecutable,
                    timeout
            );
            progress("Coverage after attempt " + coverageAttempt + ": " + currentCoverage.snapshot().describe());
            if (currentCoverage.snapshot().lineCoverage() >= TARGET_LINE_COVERAGE) {
                progress("Coverage after maximizing: " + currentCoverage.snapshot().describe());
                return latestSessionResult;
            }
        }

        throw new IllegalStateException(
                "Coverage target not met. Started at " + baselineCoverage.snapshot().describe()
                        + ", ended at " + currentCoverage.snapshot().describe()
                        + ", target is " + percentage(TARGET_LINE_COVERAGE) + " line coverage."
        );
    }

    private CoverageRun collectCoverage(
            BuildTool buildTool,
            Path projectRoot,
            Path compilationRoot,
            Path cutPath,
            Path outputPath,
            Path buildExecutable,
            Duration timeout
    ) throws Exception {
        String testSelector = fileService.deriveTestSelector(outputPath);
        String gradleProjectPath = gradleProjectPath(buildTool, projectRoot, outputPath);
        Path reportPath = jacocoReportPath(buildTool, projectRoot, compilationRoot, gradleProjectPath);
        CoverageCoordinate coverageCoordinate = coverageCoordinate(cutPath);

        ExecutionResult coverageResult = executeCoverageCommand(
                buildTool,
                projectRoot,
                compilationRoot,
                buildExecutable,
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
                            MAVEN_VALIDATION_ARGS,
                            testSelector
                    ),
                    workingDirectory(buildTool, projectRoot, compilationRoot),
                    timeout
            );
            if (!isSuccessful(retryCoverageResult)) {
                throw new IllegalStateException(
                        "Coverage collection failed after explicit JaCoCo agent retry. Failure details: "
                                + failureDetails(retryCoverageResult.output(), 8)
                );
            }
            snapshot = readJacocoLineCoverage(reportPath, coverageCoordinate);
            coverageResult = retryCoverageResult;
        }

        progress("JaCoCo report: " + reportPath);
        return new CoverageRun(snapshot, coverageResult);
    }

    private ExecutionResult executeCoverageCommand(
            BuildTool buildTool,
            Path projectRoot,
            Path compilationRoot,
            Path buildExecutable,
            Duration timeout,
            String testSelector,
            String gradleProjectPath
    ) throws Exception {
        return switch (buildTool) {
            case MAVEN -> executeBuildCommand(
                    mavenCommandBuilder.buildSingleTestCoverage(
                            projectRoot,
                            buildExecutable,
                            MAVEN_VALIDATION_ARGS,
                            testSelector
                    ),
                    workingDirectory(buildTool, projectRoot, compilationRoot),
                    timeout
            );
            case GRADLE -> runGradleCoverage(
                    projectRoot,
                    buildExecutable,
                    timeout,
                    testSelector,
                    gradleProjectPath
            );
        };
    }

    private ExecutionResult runGradleCoverage(
            Path projectRoot,
            Path buildExecutable,
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
                            GRADLE_VALIDATION_ARGS,
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

        DocumentBuilderFactory factory = secureDocumentBuilderFactory();
        String reportXml = Files.readString(reportPath, StandardCharsets.UTF_8);
        String sanitizedReportXml = stripDoctypeDeclaration(reportXml);

        try (InputStream reportStream = new ByteArrayInputStream(sanitizedReportXml.getBytes(StandardCharsets.UTF_8))) {
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

    private String stripDoctypeDeclaration(String xml) {
        if (xml == null || xml.isBlank()) {
            return "";
        }
        return DOCTYPE_DECLARATION_PATTERN.matcher(xml).replaceFirst("");
    }

    private DocumentBuilderFactory secureDocumentBuilderFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        setFeatureIfSupported(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        setFeatureIfSupported(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setFeatureIfSupported(factory, "http://xml.org/sax/features/external-general-entities", false);
        setFeatureIfSupported(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        setFeatureIfSupported(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        setAttributeIfSupported(factory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
        setAttributeIfSupported(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    private void setFeatureIfSupported(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception ignored) {
            // Some JAXP implementations reject specific feature flags.
        }
    }

    private void setAttributeIfSupported(DocumentBuilderFactory factory, String attribute, String value) {
        try {
            factory.setAttribute(attribute, value);
        } catch (Exception ignored) {
            // Attribute support differs between parser implementations.
        }
    }

    private CoverageSnapshot lineCoverageFromCounter(Path reportPath, Element sourceFileElement) {
        NodeList counters = sourceFileElement.getElementsByTagName("counter");
        List<Integer> coveredLineNumbers = coveredLineNumbers(sourceFileElement);
        List<Integer> missedLineNumbers = missedLineNumbers(sourceFileElement);
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
            return new CoverageSnapshot(reportPath, covered, missed, coveredLineNumbers, missedLineNumbers);
        }
        throw new IllegalStateException("JaCoCo report did not contain LINE counter for " + reportPath);
    }

    private List<Integer> coveredLineNumbers(Element sourceFileElement) {
        List<Integer> covered = new ArrayList<>();
        NodeList lines = sourceFileElement.getElementsByTagName("line");
        for (int index = 0; index < lines.getLength(); index++) {
            Node lineNode = lines.item(index);
            if (!(lineNode instanceof Element lineElement)) {
                continue;
            }
            int coveredInstructions = integerAttribute(lineElement, "ci");
            if (coveredInstructions <= 0) {
                continue;
            }
            int lineNumber = integerAttribute(lineElement, "nr");
            if (lineNumber > 0) {
                covered.add(lineNumber);
            }
        }
        return List.copyOf(covered);
    }

    private List<Integer> missedLineNumbers(Element sourceFileElement) {
        List<Integer> missed = new ArrayList<>();
        NodeList lines = sourceFileElement.getElementsByTagName("line");
        for (int index = 0; index < lines.getLength(); index++) {
            Node lineNode = lines.item(index);
            if (!(lineNode instanceof Element lineElement)) {
                continue;
            }
            int coveredInstructions = integerAttribute(lineElement, "ci");
            int missedInstructions = integerAttribute(lineElement, "mi");
            if (!(coveredInstructions == 0 && missedInstructions > 0)) {
                continue;
            }
            int lineNumber = integerAttribute(lineElement, "nr");
            if (lineNumber > 0) {
                missed.add(lineNumber);
            }
        }
        return List.copyOf(missed);
    }

    private int integerAttribute(Element element, String attribute) {
        try {
            return Integer.parseInt(element.getAttribute(attribute));
        } catch (NumberFormatException exception) {
            return 0;
        }
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

    private Path jacocoReportPath(
            BuildTool buildTool,
            Path projectRoot,
            Path compilationRoot,
            String gradleProjectPath
    ) {
        return switch (buildTool) {
            case MAVEN -> compilationRoot.resolve("target/site/jacoco/jacoco.xml").normalize();
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

    private String preflightFailureMessage(ValidationFailure failure) {
        return "Local build failed before JAIPilot started. Failed phase: "
                + failure.phase()
                + ". The target test file was temporarily excluded, so this failure comes from other project sources. "
                + "Resolve unrelated build failures first. Failure details: "
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

    private String buildCoverageClientLogs(Path cutPath, CoverageRun coverageRun) {
        StringBuilder builder = new StringBuilder();
        builder.append("Coverage target: ")
                .append(percentage(TARGET_LINE_COVERAGE))
                .append(" line coverage.")
                .append(System.lineSeparator());
        builder.append("Class under test: ")
                .append(cutPath)
                .append(System.lineSeparator());
        builder.append("Current line coverage: ")
                .append(coverageRun.snapshot().describe())
                .append(System.lineSeparator());
        builder.append(coverageRun.snapshot().lineBreakdown())
                .append(System.lineSeparator());
        builder.append("Coverage build output:")
                .append(System.lineSeparator())
                .append(failureDetails(coverageRun.coverageBuildResult().output(), 20))
                .append(System.lineSeparator());
        builder.append("Improve branch and edge-case assertions to raise coverage without breaking existing behavior.");
        return builder.toString();
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

    private ExecutionResult executeBuildCommand(
            List<String> command,
            Path workingDirectory,
            Duration timeout
    ) throws Exception {
        ExecutionResult result = processExecutor.execute(
                command,
                workingDirectory,
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
                    workingDirectory,
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

    private BuildTool resolveBuildTool(Path projectRoot, Path explicitBuildExecutable) {
        return fileService.detectBuildTool(projectRoot, explicitBuildExecutable)
                .orElseThrow(() -> new IllegalStateException(
                        "Unable to detect a supported build tool for " + projectRoot
                                + ". Expected pom.xml, build.gradle(.kts), or settings.gradle(.kts)."
                ));
    }

    private Path resolveCompilationRoot(
            BuildTool buildTool,
            Path projectRoot,
            Path requiredClassPath,
            Path outputPath
    ) {
        Path normalizedProjectRoot = projectRoot.toAbsolutePath().normalize();
        if (buildTool != BuildTool.MAVEN) {
            return normalizedProjectRoot;
        }

        Path moduleRoot = fileService.findNearestMavenProjectRoot(requiredClassPath);
        if (moduleRoot == null) {
            moduleRoot = fileService.findNearestMavenProjectRoot(outputPath);
        }
        if (moduleRoot == null) {
            return normalizedProjectRoot;
        }

        Path normalizedModuleRoot = moduleRoot.toAbsolutePath().normalize();
        if (!normalizedModuleRoot.startsWith(normalizedProjectRoot)) {
            return normalizedProjectRoot;
        }
        return normalizedModuleRoot;
    }

    private List<String> buildCompilationCommand(
            BuildTool buildTool,
            Path projectRoot,
            Path requiredClassPath,
            Path buildExecutable
    ) {
        return switch (buildTool) {
            case MAVEN -> mavenCommandBuilder.buildTestCompile(
                    projectRoot,
                    buildExecutable,
                    MAVEN_COMPILE_SANITY_ARGS
            );
            case GRADLE -> gradleCommandBuilder.buildTestCompile(
                    projectRoot,
                    buildExecutable,
                    GRADLE_COMPILE_SANITY_ARGS,
                    gradleProjectPath(buildTool, projectRoot, requiredClassPath)
            );
        };
    }

    private List<String> buildCodebaseRulesValidationCommand(
            BuildTool buildTool,
            Path projectRoot,
            Path sourcePath,
            Path buildExecutable
    ) {
        return switch (buildTool) {
            case MAVEN -> mavenCommandBuilder.buildCodebaseRulesValidation(
                    projectRoot,
                    buildExecutable,
                    MAVEN_VALIDATION_ARGS
            );
            case GRADLE -> gradleCommandBuilder.buildCodebaseRulesValidation(
                    projectRoot,
                    buildExecutable,
                    GRADLE_VALIDATION_ARGS,
                    gradleProjectPath(buildTool, projectRoot, sourcePath)
            );
        };
    }

    private List<String> buildSingleTestExecutionCommand(
            BuildTool buildTool,
            Path projectRoot,
            Path sourcePath,
            Path buildExecutable,
            String testSelector
    ) {
        return switch (buildTool) {
            case MAVEN -> mavenCommandBuilder.buildSingleTestExecution(
                    projectRoot,
                    buildExecutable,
                    MAVEN_VALIDATION_ARGS,
                    testSelector
            );
            case GRADLE -> gradleCommandBuilder.buildSingleTestExecution(
                    projectRoot,
                    buildExecutable,
                    GRADLE_VALIDATION_ARGS,
                    testSelector,
                    gradleProjectPath(buildTool, projectRoot, sourcePath)
            );
        };
    }

    private String gradleProjectPath(BuildTool buildTool, Path projectRoot, Path sourcePath) {
        if (buildTool != BuildTool.GRADLE) {
            return "";
        }
        return fileService.deriveGradleProjectPath(projectRoot, sourcePath);
    }

    private Path workingDirectory(BuildTool buildTool, Path projectRoot, Path compilationRoot) {
        return buildTool == BuildTool.MAVEN ? compilationRoot : projectRoot;
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
        String summarized = SensitiveDataRedactor.redactBuildOutput(output);
        if (summarized != null && !summarized.isBlank()) {
            return summarized;
        }
        return tail(SensitiveDataRedactor.redact(output), fallbackTailLines);
    }

    private String percentage(double ratio) {
        return String.format(Locale.ROOT, "%.2f%%", ratio * 100.0d);
    }

    private static String lineListSummary(List<Integer> lines) {
        if (lines == null || lines.isEmpty()) {
            return "none";
        }
        StringBuilder builder = new StringBuilder();
        int first = lines.get(0);
        int previous = first;
        for (int index = 1; index < lines.size(); index++) {
            int current = lines.get(index);
            if (current == previous + 1) {
                previous = current;
                continue;
            }
            appendRange(builder, first, previous);
            builder.append(',');
            first = current;
            previous = current;
        }
        appendRange(builder, first, previous);
        return builder.toString();
    }

    private static void appendRange(StringBuilder builder, int start, int end) {
        if (start == end) {
            builder.append(start);
            return;
        }
        builder.append(start).append('-').append(end);
    }

    private void progress(String message) {
        buildLogWriter.println("PROGRESS: " + message);
        buildLogWriter.flush();
    }

    @FunctionalInterface
    private interface CheckedOperation<T> {
        T run() throws Exception;
    }

    private record CoverageCoordinate(String packagePath, String sourceFileName) {
    }

    private record CoverageSnapshot(
            Path reportPath,
            int coveredLines,
            int missedLines,
            List<Integer> coveredLineNumbers,
            List<Integer> missedLineNumbers
    ) {
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

        String lineBreakdown() {
            return "Covered lines: " + lineListSummary(coveredLineNumbers)
                    + System.lineSeparator()
                    + "Missed lines: " + lineListSummary(missedLineNumbers);
        }
    }

    private record CoverageRun(CoverageSnapshot snapshot, ExecutionResult coverageBuildResult) {
    }

    private record ValidationPassResult(
            JunitLlmSessionResult sessionResult,
            String lastValidatedTestCode,
            boolean buildExecuted
    ) {
    }

    private record ValidationFailure(String phase, ExecutionResult result, String details) {
    }
}
