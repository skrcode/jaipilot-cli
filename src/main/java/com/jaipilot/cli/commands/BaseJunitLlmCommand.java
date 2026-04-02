package com.jaipilot.cli.commands;

import com.jaipilot.cli.JaipilotEndpointConfig;
import com.jaipilot.cli.auth.AuthService;
import com.jaipilot.cli.auth.CredentialsStore;
import com.jaipilot.cli.backend.HttpJunitLlmBackendClient;
import com.jaipilot.cli.backend.JunitLlmBackendClient;
import com.jaipilot.cli.files.ProjectFileService;
import com.jaipilot.cli.model.JunitLlmOperation;
import com.jaipilot.cli.model.JunitLlmSessionRequest;
import com.jaipilot.cli.model.JunitLlmSessionResult;
import com.jaipilot.cli.process.GradleCommandBuilder;
import com.jaipilot.cli.process.MavenCommandBuilder;
import com.jaipilot.cli.process.ProcessExecutor;
import com.jaipilot.cli.service.JunitLlmConsoleLogger;
import com.jaipilot.cli.service.JunitLlmSessionRunner;
import com.jaipilot.cli.service.JunitLlmWorkflowRunner;
import com.jaipilot.cli.service.UsedContextClassPathCache;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

abstract class BaseJunitLlmCommand implements Callable<Integer> {

    @Option(
            names = "--output",
            paramLabel = "<path>",
            description = "Output test file path. Defaults to the inferred test file."
    )
    private Path outputPath;

    @Option(
            names = {"--build-executable", "--maven-executable", "--gradle-executable"},
            paramLabel = "<path>",
            description = "Explicit build executable or wrapper path. Defaults to ./mvnw or ./gradlew from the project root, then mvn or gradle."
    )
    private Path buildExecutable;

    @Option(
            names = {"--build-arg", "--maven-arg", "--gradle-arg"},
            paramLabel = "<arg>",
            description = "Additional argument passed to the local build during validation. Repeat to supply multiple arguments."
    )
    private List<String> additionalBuildArgs = new ArrayList<>();

    @Option(
            names = "--timeout-seconds",
            defaultValue = "600",
            paramLabel = "<seconds>",
            description = "Maximum time to wait for each local build phase. Default: ${DEFAULT-VALUE}."
    )
    private long timeoutSeconds;

    @Spec
    private CommandSpec spec;

    private final ProjectFileService fileService;
    private final AuthService authService;

    BaseJunitLlmCommand() {
        this(new ProjectFileService(), new AuthService(new CredentialsStore()));
    }

    BaseJunitLlmCommand(ProjectFileService fileService) {
        this(fileService, new AuthService(new CredentialsStore()));
    }

    BaseJunitLlmCommand(ProjectFileService fileService, AuthService authService) {
        this.fileService = fileService;
        this.authService = authService;
    }

    @Override
    public final Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        JunitLlmConsoleLogger consoleLogger = new JunitLlmConsoleLogger(out);
        JunitLlmConsoleLogger errorLogger = new JunitLlmConsoleLogger(err);
        Instant startedAt = Instant.now();

        try {
            validate();
            Path workingDirectory = Path.of("").toAbsolutePath().normalize();
            Path resolvedCutPath = resolveCutPath(workingDirectory);
            Path normalizedProjectRoot = inferProjectRoot(workingDirectory, resolvedCutPath);
            Path resolvedOutputPath = outputPath == null
                    ? defaultOutputPath(workingDirectory, normalizedProjectRoot, resolvedCutPath)
                    : fileService.resolvePath(normalizedProjectRoot, outputPath);
            String initialOutputContent = Files.isRegularFile(resolvedOutputPath)
                    ? fileService.readFile(resolvedOutputPath)
                    : "";

            JunitLlmBackendClient backendClient = new HttpJunitLlmBackendClient(
                    JaipilotEndpointConfig.resolveBackendUrl(),
                    resolveJwtToken()
            );
            JunitLlmSessionRunner sessionRunner = new JunitLlmSessionRunner(
                    backendClient,
                    fileService,
                    new UsedContextClassPathCache(),
                    consoleLogger
            );
            JunitLlmWorkflowRunner workflowRunner = new JunitLlmWorkflowRunner(
                    sessionRunner,
                    new MavenCommandBuilder(),
                    new GradleCommandBuilder(),
                    new ProcessExecutor(),
                    fileService,
                    true,
                    out
            );
            JunitLlmSessionResult result = workflowRunner.run(new JunitLlmSessionRequest(
                    normalizedProjectRoot,
                    resolvedCutPath,
                    resolvedOutputPath,
                    operation(),
                    null,
                    initialTestClassCode(resolvedOutputPath),
                    "",
                    null
            ),
                    buildExecutable,
                    List.copyOf(additionalBuildArgs),
                    Duration.ofSeconds(timeoutSeconds)
            );

            consoleLogger.announceTestFile(result.outputPath());
            consoleLogger.announceTestFileDiff(initialOutputContent, fileService.readFile(result.outputPath()));
            consoleLogger.announceTotalTime(Duration.between(startedAt, Instant.now()));
            return CommandLine.ExitCode.OK;
        } catch (CommandLine.ParameterException exception) {
            throw exception;
        } catch (Exception exception) {
            errorLogger.error(exception.getMessage());
            errorLogger.announceTotalTime(Duration.between(startedAt, Instant.now()));
            return CommandLine.ExitCode.SOFTWARE;
        }
    }

    protected abstract JunitLlmOperation operation();

    protected abstract Path resolveCutPath(Path workingDirectory);

    protected abstract Path defaultOutputPath(Path workingDirectory, Path projectRoot, Path resolvedCutPath);

    protected abstract String initialTestClassCode(Path resolvedOutputPath);

    protected final ProjectFileService fileService() {
        return fileService;
    }

    private void validate() {
        if (timeoutSeconds < 1) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(),
                    "--timeout-seconds must be greater than zero."
            );
        }
    }

    private String resolveJwtToken() {
        String effectiveToken = firstNonBlank(System.getenv("JAIPILOT_JWT_TOKEN"), authService.ensureFreshAccessToken());
        if (effectiveToken == null) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(),
                    "Set JAIPILOT_JWT_TOKEN or run `jaipilot login`."
            );
        }
        return effectiveToken;
    }

    private Path inferProjectRoot(Path workingDirectory, Path resolvedCutPath) {
        Path inferredProjectRoot = fileService.findNearestBuildProjectRoot(resolvedCutPath);
        if (inferredProjectRoot == null) {
            inferredProjectRoot = fileService.findNearestBuildProjectRoot(workingDirectory);
        }
        return inferredProjectRoot != null ? inferredProjectRoot : workingDirectory;
    }

    private static String firstNonBlank(String primary, String fallback) {
        for (String value : new String[] {primary, fallback}) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
