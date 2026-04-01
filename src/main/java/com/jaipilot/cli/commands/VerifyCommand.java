package com.jaipilot.cli.commands;

import com.jaipilot.cli.bootstrap.BootstrapException;
import com.jaipilot.cli.bootstrap.MavenReactorBootstrapper;
import com.jaipilot.cli.bootstrap.MirrorBuild;
import com.jaipilot.cli.files.ProjectFileService;
import com.jaipilot.cli.process.BuildTool;
import com.jaipilot.cli.process.ExecutionResult;
import com.jaipilot.cli.process.GradleCommandBuilder;
import com.jaipilot.cli.process.MavenCommandBuilder;
import com.jaipilot.cli.process.ProcessExecutor;
import com.jaipilot.cli.report.JacocoReportParser;
import com.jaipilot.cli.report.PitReportParser;
import com.jaipilot.cli.report.VerificationEvaluator;
import com.jaipilot.cli.report.VerificationFormatter;
import com.jaipilot.cli.report.model.JacocoReport;
import com.jaipilot.cli.report.model.PitReport;
import com.jaipilot.cli.report.model.VerificationIssue;
import com.jaipilot.cli.report.model.VerificationResult;
import com.jaipilot.cli.report.model.VerificationThresholds;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
        name = "verify",
        mixinStandardHelpOptions = true,
        description = "Runs JaCoCo and PIT against a Java project and prints a PASS/FAIL report with actionable reasons."
)
public final class VerifyCommand implements Callable<Integer> {

    static final String DEFAULT_JACOCO_VERSION = "0.8.13";
    static final String DEFAULT_PIT_VERSION = "1.22.0";

    @Option(
            names = "--project-root",
            defaultValue = ".",
            paramLabel = "<dir>",
            description = "Project root containing pom.xml, build.gradle(.kts), or settings.gradle(.kts). Default: ${DEFAULT-VALUE}."
    )
    private Path projectRoot;

    @Option(
            names = "--line-coverage-threshold",
            defaultValue = "80.0",
            paramLabel = "<percent>",
            description = "Required JaCoCo line coverage percentage. Default: ${DEFAULT-VALUE}."
    )
    private double lineCoverageThreshold;

    @Option(
            names = "--branch-coverage-threshold",
            defaultValue = "70.0",
            paramLabel = "<percent>",
            description = "Required JaCoCo branch coverage percentage. Default: ${DEFAULT-VALUE}."
    )
    private double branchCoverageThreshold;

    @Option(
            names = "--instruction-coverage-threshold",
            defaultValue = "80.0",
            paramLabel = "<percent>",
            description = "Required JaCoCo instruction coverage percentage. Default: ${DEFAULT-VALUE}."
    )
    private double instructionCoverageThreshold;

    @Option(
            names = "--mutation-threshold",
            defaultValue = "70.0",
            paramLabel = "<percent>",
            description = "Required PIT mutation score percentage. Default: ${DEFAULT-VALUE}."
    )
    private double mutationThreshold;

    @Option(
            names = {"--build-executable", "--maven-executable", "--gradle-executable"},
            paramLabel = "<path>",
            description = "Explicit build executable or wrapper path. Defaults to ./mvnw or ./gradlew, then mvn or gradle."
    )
    private Path buildExecutable;

    @Option(
            names = {"--build-arg", "--maven-arg", "--gradle-arg"},
            paramLabel = "<arg>",
            description = "Additional argument passed to the build tool. Repeat to supply multiple arguments."
    )
    private List<String> additionalBuildArgs = new ArrayList<>();

    @Option(
            names = "--jacoco-version",
            defaultValue = DEFAULT_JACOCO_VERSION,
            paramLabel = "<version>",
            description = "JaCoCo plugin version used by Maven zero-config verification. Default: ${DEFAULT-VALUE}."
    )
    private String jacocoVersion;

    @Option(
            names = "--pit-version",
            defaultValue = DEFAULT_PIT_VERSION,
            paramLabel = "<version>",
            description = "PIT plugin version used by Maven zero-config verification. Default: ${DEFAULT-VALUE}."
    )
    private String pitVersion;

    @Option(
            names = "--timeout-seconds",
            defaultValue = "1800",
            paramLabel = "<seconds>",
            description = "Maximum time to wait for build execution. Default: ${DEFAULT-VALUE}."
    )
    private long timeoutSeconds;

    @Option(
            names = "--max-actionable-items",
            defaultValue = "5",
            paramLabel = "<count>",
            description = "Maximum detailed findings to include per failing section. Default: ${DEFAULT-VALUE}."
    )
    private int maxActionableItems;

    @Option(
            names = "--skip-clean",
            description = "Skip the initial clean task/goal."
    )
    private boolean skipClean;

    @Option(
            names = "--verbose",
            description = "Prints the executed build command and streamed build output to stderr."
    )
    private boolean verbose;

    @Spec
    private CommandSpec spec;

    private final ProjectFileService fileService;
    private final MavenCommandBuilder commandBuilder;
    private final GradleCommandBuilder gradleCommandBuilder;
    private final ProcessExecutor processExecutor;
    private final JacocoReportParser jacocoReportParser;
    private final PitReportParser pitReportParser;
    private final VerificationEvaluator verificationEvaluator;
    private final VerificationFormatter verificationFormatter;
    private final MavenReactorBootstrapper bootstrapper;

    public VerifyCommand() {
        this(
                new ProjectFileService(),
                new MavenCommandBuilder(),
                new GradleCommandBuilder(),
                new ProcessExecutor(),
                new JacocoReportParser(),
                new PitReportParser(),
                new VerificationEvaluator(),
                new VerificationFormatter(),
                new MavenReactorBootstrapper()
        );
    }

    VerifyCommand(
            ProjectFileService fileService,
            MavenCommandBuilder commandBuilder,
            GradleCommandBuilder gradleCommandBuilder,
            ProcessExecutor processExecutor,
            JacocoReportParser jacocoReportParser,
            PitReportParser pitReportParser,
            VerificationEvaluator verificationEvaluator,
            VerificationFormatter verificationFormatter,
            MavenReactorBootstrapper bootstrapper
    ) {
        this.fileService = fileService;
        this.commandBuilder = commandBuilder;
        this.gradleCommandBuilder = gradleCommandBuilder;
        this.processExecutor = processExecutor;
        this.jacocoReportParser = jacocoReportParser;
        this.pitReportParser = pitReportParser;
        this.verificationEvaluator = verificationEvaluator;
        this.verificationFormatter = verificationFormatter;
        this.bootstrapper = bootstrapper;
    }

    @Override
    public Integer call() throws Exception {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        Path requestedProjectRoot = projectRoot.toAbsolutePath().normalize();
        VerificationThresholds thresholds = thresholdsForEvaluation();

        MirrorBuild mirrorBuild = null;
        boolean keepWorkspace = false;
        Path normalizedProjectRoot = requestedProjectRoot;

        try {
            validateConfiguration();
            Path inferredProjectRoot = fileService.findNearestBuildProjectRoot(requestedProjectRoot);
            normalizedProjectRoot = inferredProjectRoot != null ? inferredProjectRoot : requestedProjectRoot;
            BuildTool buildTool = fileService.detectBuildTool(normalizedProjectRoot, buildExecutable).orElse(null);

            if (buildTool == null) {
                return fail(
                        out,
                        normalizedProjectRoot,
                        thresholds,
                        List.of(new VerificationIssue(
                                "No supported build file was found at the provided project root.",
                                "Run `jaipilot verify` from a Maven or Gradle project root, or pass --project-root."
                        )),
                        null
                );
            }

            progress(err, "Inspecting " + buildTool.displayName() + " project at " + normalizedProjectRoot);

            VerificationResult verificationResult;
            if (buildTool == BuildTool.MAVEN) {
                MavenVerificationRun run = runMavenVerification(normalizedProjectRoot, err);
                mirrorBuild = run.mirrorBuild();
                verificationResult = run.result();
            } else {
                verificationResult = runGradleVerification(normalizedProjectRoot, err);
            }

            if (verbose && !verificationResult.successful()) {
                Path debugWorkspace = mirrorBuild != null ? mirrorBuild.tempProjectRoot() : normalizedProjectRoot;
                keepWorkspace = mirrorBuild != null;
                verificationResult = withDebugWorkspace(verificationResult, debugWorkspace);
            }

            out.print(verificationFormatter.format(verificationResult));
            out.flush();
            return verificationResult.successful() ? 0 : 1;
        } catch (BootstrapException exception) {
            Path debugWorkspace = verbose ? exception.workspacePath() : null;
            keepWorkspace = verbose && debugWorkspace != null;
            return fail(out, normalizedProjectRoot, thresholds, List.of(exception.issue()), debugWorkspace);
        } catch (IllegalArgumentException exception) {
            return fail(
                    out,
                    normalizedProjectRoot,
                    thresholds,
                    List.of(new VerificationIssue(
                            "Invalid verify configuration.",
                            exception.getMessage()
                    )),
                    null
            );
        } catch (Exception exception) {
            Path debugWorkspace = verbose && mirrorBuild != null ? mirrorBuild.tempProjectRoot() : null;
            keepWorkspace = keepWorkspace || debugWorkspace != null;
            return fail(
                    out,
                    normalizedProjectRoot,
                    thresholds,
                    List.of(new VerificationIssue(
                            "JAIPilot verification failed before the report could be fully produced.",
                            "Resolve the execution error and rerun the command. Error: " + describeException(exception)
                    )),
                    debugWorkspace
            );
        } finally {
            if (mirrorBuild != null && !keepWorkspace) {
                try {
                    mirrorBuild.cleanup();
                } catch (Exception cleanupException) {
                    if (verbose) {
                        err.println("Failed to clean mirrored workspace " + mirrorBuild.tempProjectRoot()
                                + ": " + cleanupException.getMessage());
                        err.flush();
                    }
                }
            }
        }
    }

    private MavenVerificationRun runMavenVerification(Path projectRoot, PrintWriter err) throws Exception {
        if (!Files.isRegularFile(projectRoot.resolve("pom.xml"))) {
            throw new BootstrapException(new VerificationIssue(
                    "No pom.xml was found at the provided project root.",
                    "Run `jaipilot verify` from a Maven project root or pass --project-root."
            ));
        }

        progress(err, "Preparing temporary Maven workspace");
        MirrorBuild mirrorBuild = bootstrapper.prepare(projectRoot, jacocoVersion, pitVersion);
        List<String> command = commandBuilder.build(
                mirrorBuild.tempProjectRoot(),
                mirrorBuild.buildPomPath(),
                buildExecutable,
                additionalBuildArgs,
                jacocoVersion,
                pitVersion,
                skipClean,
                mirrorBuild.runAggregateCoverage()
        );

        if (verbose) {
            err.println("Running Maven verification from mirrored workspace " + mirrorBuild.tempProjectRoot());
            err.println(String.join(" ", command));
            err.flush();
        }

        progress(err, "Running Maven tests, JaCoCo, and PIT");
        ExecutionResult executionResult = processExecutor.execute(
                command,
                mirrorBuild.tempProjectRoot(),
                Duration.ofSeconds(timeoutSeconds),
                verbose,
                err
        );

        progress(err, "Parsing JaCoCo and PIT reports");
        Optional<JacocoReport> jacocoReport = jacocoReportParser.parse(
                mirrorBuild.tempProjectRoot(),
                projectRoot
        );
        Optional<PitReport> pitReport = pitReportParser.parse(
                mirrorBuild.tempProjectRoot(),
                projectRoot
        );

        progress(err, "Evaluating thresholds and formatting the final report");
        VerificationResult verificationResult = verificationEvaluator.evaluate(
                projectRoot,
                executionResult,
                jacocoReport,
                pitReport,
                thresholdsForEvaluation(),
                maxActionableItems,
                null
        );
        return new MavenVerificationRun(mirrorBuild, verificationResult);
    }

    private VerificationResult runGradleVerification(Path projectRoot, PrintWriter err) throws Exception {
        List<String> command = gradleCommandBuilder.buildVerification(
                projectRoot,
                buildExecutable,
                additionalBuildArgs,
                skipClean
        );

        if (verbose) {
            err.println("Running Gradle verification from project root " + projectRoot);
            err.println(String.join(" ", command));
            err.flush();
        }

        progress(err, "Running Gradle tests, JaCoCo, and PIT");
        ExecutionResult executionResult = processExecutor.execute(
                command,
                projectRoot,
                Duration.ofSeconds(timeoutSeconds),
                verbose,
                err
        );

        progress(err, "Parsing JaCoCo and PIT reports");
        Optional<JacocoReport> jacocoReport = jacocoReportParser.parse(projectRoot, projectRoot);
        Optional<PitReport> pitReport = pitReportParser.parse(projectRoot, projectRoot);

        progress(err, "Evaluating thresholds and formatting the final report");
        return verificationEvaluator.evaluate(
                projectRoot,
                executionResult,
                jacocoReport,
                pitReport,
                thresholdsForEvaluation(),
                maxActionableItems,
                null
        );
    }

    private VerificationResult withDebugWorkspace(VerificationResult result, Path debugWorkspace) {
        return new VerificationResult(
                result.projectRoot(),
                result.successful(),
                result.thresholds(),
                result.checks(),
                result.coverageSummary(),
                result.coverageFindings(),
                result.omittedCoverageFindings(),
                result.mutationSummary(),
                result.mutationFindings(),
                result.omittedMutationFindings(),
                result.buildIssues(),
                debugWorkspace
        );
    }

    private void validateConfiguration() {
        ensurePercentage("line coverage threshold", lineCoverageThreshold);
        ensurePercentage("branch coverage threshold", branchCoverageThreshold);
        ensurePercentage("instruction coverage threshold", instructionCoverageThreshold);
        ensurePercentage("mutation threshold", mutationThreshold);
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("--timeout-seconds must be greater than 0.");
        }
        if (maxActionableItems <= 0) {
            throw new IllegalArgumentException("--max-actionable-items must be greater than 0.");
        }
    }

    private VerificationThresholds thresholdsForEvaluation() {
        return new VerificationThresholds(
                lineCoverageThreshold,
                branchCoverageThreshold,
                instructionCoverageThreshold,
                mutationThreshold
        );
    }

    private static void ensurePercentage(String name, double value) {
        if (Double.isNaN(value) || value < 0.0d || value > 100.0d) {
            throw new IllegalArgumentException(name + " must be between 0 and 100.");
        }
    }

    private static String describeException(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private void progress(PrintWriter err, String message) {
        err.println("PROGRESS: " + message);
        err.flush();
    }

    private int fail(
            PrintWriter out,
            Path projectRoot,
            VerificationThresholds thresholds,
            List<VerificationIssue> issues,
            Path debugWorkspace
    ) {
        out.print(verificationFormatter.formatFailure(projectRoot, thresholds, issues, debugWorkspace));
        out.flush();
        return 1;
    }

    private record MavenVerificationRun(MirrorBuild mirrorBuild, VerificationResult result) {
    }
}
