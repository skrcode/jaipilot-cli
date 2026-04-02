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

public final class JunitLlmWorkflowRunner {

    private static final PrintWriter QUIET_WRITER = new PrintWriter(Writer.nullWriter());
    private static final int MAX_FIX_ATTEMPTS = 20;

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

        JunitLlmSessionResult latestSessionResult = runSessionWithDependencySourceRetry(
                initialRequest,
                buildTool,
                buildExecutable,
                additionalBuildArgs,
                timeout
        );

        ValidationFailure validationFailure = validateLocalBuild(
                buildTool,
                initialRequest.projectRoot(),
                latestSessionResult.outputPath(),
                buildExecutable,
                additionalBuildArgs,
                timeout
        );
        if (validationFailure == null) {
            return latestSessionResult;
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
            validationFailure = validateLocalBuild(
                    buildTool,
                    initialRequest.projectRoot(),
                    latestSessionResult.outputPath(),
                    buildExecutable,
                    additionalBuildArgs,
                    timeout
            );
        }

        if (validationFailure == null) {
            return latestSessionResult;
        }
        throw new IllegalStateException(localValidationFailureMessage(validationFailure));
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

    private String preflightFailureMessage(ValidationFailure failure) {
        return "Local build failed before JAIPilot started. The target test file was temporarily excluded, so this failure comes from other project sources. "
                + "Resolve the unrelated compile errors first. Output tail: "
                + tail(SensitiveDataRedactor.redact(failure.result().output()), 6);
    }

    private String localValidationFailureMessage(ValidationFailure failure) {
        return "Generated/fixed test did not pass local validation. Failed phase: "
                + failure.phase()
                + ". Output tail: "
                + tail(SensitiveDataRedactor.redact(failure.result().output()), 6);
    }

    private String buildFixClientLogs(ValidationFailure failure) {
        StringBuilder builder = new StringBuilder();
        builder.append("Failed phase: ")
                .append(failure.phase())
                .append(System.lineSeparator());
        if (failure.details() != null && !failure.details().isBlank()) {
            builder.append(failure.details()).append(System.lineSeparator());
        }
        builder.append("Build output tail: ")
                .append(tail(SensitiveDataRedactor.redact(failure.result().output()), 20));
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

    private void tryDownloadDependencySources(
            BuildTool buildTool,
            Path projectRoot,
            Path buildExecutable,
            List<String> additionalBuildArgs,
            Duration timeout
    ) {
        try {
            switch (buildTool) {
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
            }
        } catch (Exception ignored) {
            // Best effort: if source download fails, the second backend/context attempt will report the original miss.
        }
    }

    private void runGradleDependencySourceDownload(
            Path projectRoot,
            Path buildExecutable,
            List<String> additionalBuildArgs,
            Duration timeout
    ) throws Exception {
        Path initScript = Files.createTempFile("jaipilot-gradle-sources-", ".gradle");
        try {
            Files.writeString(initScript, GRADLE_DEPENDENCY_SOURCES_INIT_SCRIPT, StandardCharsets.UTF_8);
            executeBuildCommand(
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
            List<String> fallbackCommand = new ArrayList<>(command);
            fallbackCommand.set(0, systemMavenCommand());
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
        return output.contains("mavenwrappermain")
                || output.contains("maven-wrapper.properties file does not exist")
                || output.contains("could not find or load main class org.apache.maven.wrapper.mavenwrappermain");
    }

    private String systemMavenCommand() {
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
        return windows ? "mvn.cmd" : "mvn";
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
