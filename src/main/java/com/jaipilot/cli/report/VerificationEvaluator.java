package com.jaipilot.cli.report;

import com.jaipilot.cli.process.ExecutionResult;
import com.jaipilot.cli.report.model.CheckResult;
import com.jaipilot.cli.report.model.CoverageFinding;
import com.jaipilot.cli.report.model.CoverageMetric;
import com.jaipilot.cli.report.model.CoverageSummary;
import com.jaipilot.cli.report.model.JacocoMethodCoverage;
import com.jaipilot.cli.report.model.JacocoReport;
import com.jaipilot.cli.report.model.JacocoSourceFileCoverage;
import com.jaipilot.cli.report.model.VerificationIssue;
import com.jaipilot.cli.report.model.VerificationResult;
import com.jaipilot.cli.report.model.VerificationThresholds;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class VerificationEvaluator {

    private static final int UNCOVERED_LINE_LIMIT = 10;

    public VerificationResult evaluate(
            Path projectRoot,
            ExecutionResult executionResult,
            Optional<JacocoReport> jacocoReport,
            VerificationThresholds thresholds,
            int maxActionableItems,
            Path debugWorkspace
    ) {
        List<CheckResult> checks = new ArrayList<>();
        Map<CoverageMetric, List<CoverageFinding>> coverageFindings = new LinkedHashMap<>();
        Map<CoverageMetric, Integer> omittedCoverageFindings = new LinkedHashMap<>();
        List<VerificationIssue> buildIssues = new ArrayList<>();
        CoverageSummary coverageSummary = null;

        boolean executionFailed = false;
        if (executionResult.timedOut()) {
            executionFailed = true;
            buildIssues.add(new VerificationIssue(
                    "Verification timed out after the configured timeout window.",
                    "Increase --timeout-seconds or reduce the test scope, then rerun `jaipilot verify`."
            ));
        } else if (executionResult.exitCode() != 0) {
            executionFailed = true;
            buildIssues.add(classifyExecutionFailure(executionResult));
        }

        if (jacocoReport.isPresent()) {
            JacocoReport report = jacocoReport.get();
            coverageSummary = new CoverageSummary(
                    report.moduleCount(),
                    report.sourceFileCount(),
                    report.classCount(),
                    report.methodCount(),
                    report.lineCounter().percentage(),
                    report.branchCounter().percentage(),
                    report.instructionCounter().percentage()
            );
            addCoverageSection(
                    report,
                    CoverageMetric.LINE,
                    thresholds.lineCoverageThreshold(),
                    maxActionableItems,
                    checks,
                    coverageFindings,
                    omittedCoverageFindings
            );
            addCoverageSection(
                    report,
                    CoverageMetric.BRANCH,
                    thresholds.branchCoverageThreshold(),
                    maxActionableItems,
                    checks,
                    coverageFindings,
                    omittedCoverageFindings
            );
            addCoverageSection(
                    report,
                    CoverageMetric.INSTRUCTION,
                    thresholds.instructionCoverageThreshold(),
                    maxActionableItems,
                    checks,
                    coverageFindings,
                    omittedCoverageFindings
            );
        } else if (!executionFailed) {
            buildIssues.add(new VerificationIssue(
                    "JaCoCo XML output was not generated anywhere under the build workspace.",
                    "Ensure the project tests run successfully and that JaCoCo report generation is not disabled."
            ));
        }

        boolean successful = buildIssues.isEmpty() && checks.stream().allMatch(CheckResult::passed);
        return new VerificationResult(
                projectRoot,
                successful,
                thresholds,
                List.copyOf(checks),
                coverageSummary,
                Map.copyOf(coverageFindings),
                Map.copyOf(omittedCoverageFindings),
                List.copyOf(buildIssues),
                debugWorkspace
        );
    }

    private void addCoverageSection(
            JacocoReport report,
            CoverageMetric metric,
            double threshold,
            int maxActionableItems,
            List<CheckResult> checks,
            Map<CoverageMetric, List<CoverageFinding>> coverageFindings,
            Map<CoverageMetric, Integer> omittedCoverageFindings
    ) {
        double actual = report.counter(metric).percentage();
        boolean passed = actual >= threshold;
        checks.add(new CheckResult("jacoco." + metric.label().toLowerCase(Locale.ROOT), passed, actual, threshold));
        if (passed) {
            return;
        }

        List<JacocoSourceFileCoverage> failingFiles = report.sourceFiles().stream()
                .filter(sourceFile -> sourceFile.counter(metric).missed() > 0)
                .sorted(Comparator
                        .comparingDouble((JacocoSourceFileCoverage sourceFile) -> sourceFile.counter(metric).percentage())
                        .thenComparing(sourceFile -> sourceFile.sourceFilePath().toString()))
                .toList();

        int endIndex = Math.min(failingFiles.size(), maxActionableItems);
        coverageFindings.put(
                metric,
                failingFiles.subList(0, endIndex).stream()
                        .map(sourceFile -> toCoverageFinding(metric, sourceFile))
                        .toList()
        );
        omittedCoverageFindings.put(metric, Math.max(0, failingFiles.size() - endIndex));
    }

    private CoverageFinding toCoverageFinding(CoverageMetric metric, JacocoSourceFileCoverage sourceFile) {
        JacocoMethodCoverage lowestMethod = sourceFile.lowestCoverageMethod(metric);
        String className = lowestMethod == null ? fallbackClassName(sourceFile) : lowestMethod.className();
        String methodName = lowestMethod == null ? "" : lowestMethod.displayName();
        return new CoverageFinding(
                metric,
                sourceFile.modulePath(),
                sourceFile.sourceFilePath(),
                className,
                methodName,
                sourceFile.counter(metric).percentage(),
                sourceFile.uncoveredLines(UNCOVERED_LINE_LIMIT),
                sourceFile.branchDeficitLines(UNCOVERED_LINE_LIMIT),
                coverageAction(metric)
        );
    }

    private String fallbackClassName(JacocoSourceFileCoverage sourceFile) {
        String simpleName = sourceFile.sourceFileName().endsWith(".java")
                ? sourceFile.sourceFileName().substring(0, sourceFile.sourceFileName().length() - 5)
                : sourceFile.sourceFileName();
        return sourceFile.packageName().isBlank() ? simpleName : sourceFile.packageName() + "." + simpleName;
    }

    private String coverageAction(CoverageMetric metric) {
        return switch (metric) {
            case LINE -> "Add tests that execute the uncovered lines and assert the expected results.";
            case BRANCH -> "Add tests for the missing false, guard, and error branches on the listed lines.";
            case INSTRUCTION -> "Exercise the unexecuted method paths and strengthen assertions around side effects.";
        };
    }

    private VerificationIssue classifyExecutionFailure(ExecutionResult executionResult) {
        String output = executionResult.output();

        if (isMissingGradleJacocoTask(output)) {
            return new VerificationIssue(
                    "Gradle verification could not find a `jacocoTestReport` task.",
                    "Apply the `jacoco` plugin and enable XML reports in the Gradle project before rerunning verification."
            );
        }

        return new VerificationIssue(
                "Build verification exited with code " + executionResult.exitCode() + ".",
                "Fix the failing build or test error. Output tail: " + tail(executionResult.output(), 6)
        );
    }

    private boolean isMissingGradleJacocoTask(String output) {
        String normalizedOutput = output.toLowerCase(Locale.ROOT);
        return normalizedOutput.contains("task 'jacocotestreport' not found");
    }

    private static String tail(String output, int maxLines) {
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
}
