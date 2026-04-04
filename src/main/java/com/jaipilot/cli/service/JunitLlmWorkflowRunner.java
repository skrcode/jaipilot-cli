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
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class JunitLlmWorkflowRunner {

    private static final PrintWriter QUIET_WRITER = new PrintWriter(Writer.nullWriter());
    private static final int MAX_FIX_ATTEMPTS = 20;
    private static final List<String> MAVEN_COMPILE_SANITY_ARGS = List.of("-q");
    private static final List<String> GRADLE_COMPILE_SANITY_ARGS = List.of("--quiet");
    private static final List<String> MAVEN_VALIDATION_ARGS = List.of("-q");
    private static final List<String> GRADLE_VALIDATION_ARGS = List.of("--quiet");

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

        return fixUntilValidationPasses(
                initialRequest,
                latestSessionResult,
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

    private JunitLlmSessionResult fixUntilValidationPasses(
            JunitLlmSessionRequest initialRequest,
            JunitLlmSessionResult latestSessionResult,
            BuildTool buildTool,
            Path compilationRoot,
            Path buildExecutable,
            Duration timeout
    ) throws Exception {
        String lastValidatedTestCode = normalizeTestCode(initialRequest.initialTestClassCode());
        String currentTestCode = readTestCode(latestSessionResult.outputPath());

        ValidationFailure validationFailure = validateLocalBuild(
                buildTool,
                initialRequest.projectRoot(),
                compilationRoot,
                initialRequest.cutPath(),
                latestSessionResult.outputPath(),
                buildExecutable,
                timeout
        );
        lastValidatedTestCode = currentTestCode;

        if (validationFailure == null) {
            return latestSessionResult;
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
            if (!hasTestCodeChanged(lastValidatedTestCode, currentTestCode)) {
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
            lastValidatedTestCode = currentTestCode;
            if (validationFailure == null) {
                return latestSessionResult;
            }
        }

        throw new IllegalStateException(localValidationFailureMessage(validationFailure));
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
        if (output == null || output.isBlank()) {
            return "no build output was captured";
        }
        return output;
    }

    private void progress(String message) {
        buildLogWriter.println("PROGRESS: " + message);
        buildLogWriter.flush();
    }

    @FunctionalInterface
    private interface CheckedOperation<T> {
        T run() throws Exception;
    }

    private record ValidationFailure(String phase, ExecutionResult result, String details) {
    }
}
