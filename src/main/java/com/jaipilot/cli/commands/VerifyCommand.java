package com.jaipilot.cli.commands;

import com.jaipilot.cli.bootstrap.BootstrapException;
import com.jaipilot.cli.bootstrap.MavenReactorBootstrapper;
import com.jaipilot.cli.bootstrap.MirrorBuild;
import com.jaipilot.cli.process.ExecutionResult;
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
        description = "Runs JaCoCo and PIT against a Maven project and prints a PASS/FAIL report with actionable reasons."
)
public final class VerifyCommand implements Callable<Integer> {

    static final String DEFAULT_JACOCO_VERSION = "0.8.13";
    static final String DEFAULT_PIT_VERSION = "1.22.0";

    @Option(
            names = "--project-root",
            defaultValue = ".",
            paramLabel = "<dir>",
            description = "Project root containing pom.xml. Default: ${DEFAULT-VALUE}."
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
            names = "--maven-executable",
            paramLabel = "<path>",
            description = "Explicit Maven executable or wrapper path. Defaults to ./mvnw or mvn."
    )
    private Path mavenExecutable;

    @Option(
            names = "--maven-arg",
            paramLabel = "<arg>",
            description = "Additional argument passed to Maven. Repeat to supply multiple arguments."
    )
    private List<String> additionalMavenArgs = new ArrayList<>();

    @Option(
            names = "--jacoco-version",
            defaultValue = DEFAULT_JACOCO_VERSION,
            paramLabel = "<version>",
            description = "JaCoCo Maven plugin version to invoke. Default: ${DEFAULT-VALUE}."
    )
    private String jacocoVersion;

    @Option(
            names = "--pit-version",
            defaultValue = DEFAULT_PIT_VERSION,
            paramLabel = "<version>",
            description = "PIT Maven plugin version to invoke. Default: ${DEFAULT-VALUE}."
    )
    private String pitVersion;

    @Option(
            names = "--timeout-seconds",
            defaultValue = "1800",
            paramLabel = "<seconds>",
            description = "Maximum time to wait for Maven execution. Default: ${DEFAULT-VALUE}."
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
            description = "Skip the initial Maven clean goal."
    )
    private boolean skipClean;

    @Option(
            names = "--verbose",
            description = "Prints the executed Maven command and streamed Maven output to stderr."
    )
    private boolean verbose;

    @Spec
    private CommandSpec spec;

    private final MavenCommandBuilder commandBuilder;
    private final ProcessExecutor processExecutor;
    private final JacocoReportParser jacocoReportParser;
    private final PitReportParser pitReportParser;
    private final VerificationEvaluator verificationEvaluator;
    private final VerificationFormatter verificationFormatter;
    private final MavenReactorBootstrapper bootstrapper;

    public VerifyCommand() {
        this(
                new MavenCommandBuilder(),
                new ProcessExecutor(),
                new JacocoReportParser(),
                new PitReportParser(),
                new VerificationEvaluator(),
                new VerificationFormatter(),
                new MavenReactorBootstrapper()
        );
    }

    VerifyCommand(
            MavenCommandBuilder commandBuilder,
            ProcessExecutor processExecutor,
            JacocoReportParser jacocoReportParser,
            PitReportParser pitReportParser,
            VerificationEvaluator verificationEvaluator,
            VerificationFormatter verificationFormatter,
            MavenReactorBootstrapper bootstrapper
    ) {
        this.commandBuilder = commandBuilder;
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
        Path normalizedProjectRoot = projectRoot.toAbsolutePath().normalize();
        VerificationThresholds thresholds = new VerificationThresholds(
                lineCoverageThreshold,
                branchCoverageThreshold,
                instructionCoverageThreshold,
                mutationThreshold
        );

        MirrorBuild mirrorBuild = null;
        boolean keepWorkspace = false;

        try {
            progress(err, "Inspecting Maven project at " + normalizedProjectRoot);
            validateConfiguration();

            if (!Files.isRegularFile(normalizedProjectRoot.resolve("pom.xml"))) {
                return fail(
                        out,
                        normalizedProjectRoot,
                        thresholds,
                        List.of(new VerificationIssue(
                                "No pom.xml was found at the provided project root.",
                                "Run `jaipilot verify` from a Maven project root or pass --project-root."
                        )),
                        null
                );
            }

            progress(err, "Preparing temporary Maven workspace");
            mirrorBuild = bootstrapper.prepare(normalizedProjectRoot, jacocoVersion, pitVersion);
            List<String> command = commandBuilder.build(
                    mirrorBuild.tempProjectRoot(),
                    mirrorBuild.buildPomPath(),
                    mavenExecutable,
                    additionalMavenArgs,
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
                    normalizedProjectRoot
            );
            Optional<PitReport> pitReport = pitReportParser.parse(
                    mirrorBuild.tempProjectRoot(),
                    normalizedProjectRoot
            );

            progress(err, "Evaluating thresholds and formatting the final report");
            VerificationResult verificationResult = verificationEvaluator.evaluate(
                    normalizedProjectRoot,
                    executionResult,
                    jacocoReport,
                    pitReport,
                    thresholds,
                    maxActionableItems,
                    null
            );
            if (verbose && !verificationResult.successful()) {
                keepWorkspace = true;
                verificationResult = withDebugWorkspace(verificationResult, mirrorBuild.tempProjectRoot());
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
}
