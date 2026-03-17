package com.jaipilot.cli.report;

import com.jaipilot.cli.report.model.CheckResult;
import com.jaipilot.cli.report.model.CoverageFinding;
import com.jaipilot.cli.report.model.CoverageMetric;
import com.jaipilot.cli.report.model.CoverageSummary;
import com.jaipilot.cli.report.model.MutationFinding;
import com.jaipilot.cli.report.model.MutationSummary;
import com.jaipilot.cli.report.model.VerificationIssue;
import com.jaipilot.cli.report.model.VerificationResult;
import com.jaipilot.cli.report.model.VerificationThresholds;
import com.jaipilot.cli.util.PercentFormatter;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

public final class VerificationFormatter {

    public String format(VerificationResult result) {
        StringBuilder builder = new StringBuilder();
        builder.append("STATUS: ")
                .append(result.successful() ? "PASS" : "FAIL")
                .append(System.lineSeparator());
        builder.append("PROJECT_ROOT: ")
                .append(result.projectRoot())
                .append(System.lineSeparator());
        builder.append("THRESHOLDS: ")
                .append("line=").append(PercentFormatter.format(result.thresholds().lineCoverageThreshold()))
                .append(" branch=").append(PercentFormatter.format(result.thresholds().branchCoverageThreshold()))
                .append(" instruction=").append(PercentFormatter.format(result.thresholds().instructionCoverageThreshold()))
                .append(" mutation=").append(PercentFormatter.format(result.thresholds().mutationThreshold()))
                .append(System.lineSeparator());

        for (CheckResult check : result.checks()) {
            builder.append("CHECK: ")
                    .append("key=").append(check.key())
                    .append(" status=").append(check.passed() ? "PASS" : "FAIL")
                    .append(" actual=").append(PercentFormatter.format(check.actual()))
                    .append(" threshold=").append(PercentFormatter.format(check.threshold()))
                    .append(System.lineSeparator());
        }

        appendCoverageSummary(builder, result.coverageSummary());
        appendCoverageFindings(builder, result.coverageFindings(), result.omittedCoverageFindings());
        appendMutationSummary(builder, result.mutationSummary());
        appendMutationFindings(builder, result.mutationFindings(), result.omittedMutationFindings());
        appendBuildIssues(builder, result.buildIssues());

        if (result.debugWorkspace() != null) {
            builder.append("DEBUG_WORKSPACE: ")
                    .append(result.debugWorkspace())
                    .append(System.lineSeparator());
        }

        builder.append("NEXT_STEP: ")
                .append(nextStep(result))
                .append(System.lineSeparator());
        return builder.toString();
    }

    public String formatFailure(
            Path projectRoot,
            VerificationThresholds thresholds,
            List<VerificationIssue> buildIssues,
            Path debugWorkspace
    ) {
        return format(new VerificationResult(
                projectRoot,
                false,
                thresholds,
                List.of(),
                null,
                Map.of(),
                Map.of(),
                null,
                List.of(),
                0,
                List.copyOf(buildIssues),
                debugWorkspace
        ));
    }

    private void appendCoverageSummary(StringBuilder builder, CoverageSummary summary) {
        if (summary == null) {
            builder.append("COVERAGE_SUMMARY: NOT_AVAILABLE").append(System.lineSeparator());
            return;
        }
        builder.append("COVERAGE_SUMMARY: ")
                .append("modules=").append(summary.moduleCount())
                .append(" sourceFiles=").append(summary.sourceFileCount())
                .append(" classes=").append(summary.classCount())
                .append(" methods=").append(summary.methodCount())
                .append(" lineCoverage=").append(PercentFormatter.format(summary.lineCoverage()))
                .append(" branchCoverage=").append(PercentFormatter.format(summary.branchCoverage()))
                .append(" instructionCoverage=").append(PercentFormatter.format(summary.instructionCoverage()))
                .append(System.lineSeparator());
    }

    private void appendCoverageFindings(
            StringBuilder builder,
            Map<CoverageMetric, List<CoverageFinding>> findingsByMetric,
            Map<CoverageMetric, Integer> omittedByMetric
    ) {
        boolean hasFindings = false;
        for (CoverageMetric metric : CoverageMetric.values()) {
            List<CoverageFinding> findings = findingsByMetric.getOrDefault(metric, List.of());
            if (findings.isEmpty()) {
                continue;
            }
            hasFindings = true;
            for (CoverageFinding finding : findings) {
                builder.append("COVERAGE_FINDING: ")
                        .append("metric=").append(finding.metric().label())
                        .append(" module=").append(displayPath(finding.modulePath()))
                        .append(" file=").append(displayPath(finding.sourceFilePath()))
                        .append(" class=").append(quoted(orDash(finding.className())))
                        .append(" method=").append(quoted(orDash(finding.methodName())))
                        .append(" actual=").append(PercentFormatter.format(finding.actual()))
                        .append(" uncoveredLines=").append(formatNumbers(finding.uncoveredLines()))
                        .append(" branchDeficitLines=").append(formatNumbers(finding.branchDeficitLines()))
                        .append(" action=").append(quoted(finding.action()))
                        .append(System.lineSeparator());
            }
            int omitted = omittedByMetric.getOrDefault(metric, 0);
            if (omitted > 0) {
                builder.append("COVERAGE_FINDINGS_OMITTED: ")
                        .append("metric=").append(metric.label())
                        .append(" count=").append(omitted)
                        .append(System.lineSeparator());
            }
        }
        if (!hasFindings) {
            builder.append("COVERAGE_FINDING: NONE").append(System.lineSeparator());
        }
    }

    private void appendMutationSummary(StringBuilder builder, MutationSummary summary) {
        if (summary == null) {
            builder.append("MUTATION_SUMMARY: NOT_AVAILABLE").append(System.lineSeparator());
            return;
        }
        builder.append("MUTATION_SUMMARY: ")
                .append("total=").append(summary.totalMutations())
                .append(" detected=").append(summary.detectedMutations())
                .append(" mutationScore=").append(PercentFormatter.format(summary.mutationScore()))
                .append(" statuses=").append(formatStatusCounts(summary.statusCounts()))
                .append(System.lineSeparator());
    }

    private void appendMutationFindings(
            StringBuilder builder,
            List<MutationFinding> findings,
            int omittedCount
    ) {
        if (findings.isEmpty()) {
            builder.append("MUTATION_FINDING: NONE").append(System.lineSeparator());
            return;
        }
        for (MutationFinding finding : findings) {
            builder.append("MUTATION_FINDING: ")
                    .append("module=").append(displayPath(finding.modulePath()))
                    .append(" file=").append(displayPath(finding.sourceFilePath()))
                    .append(" class=").append(quoted(orDash(finding.className())))
                    .append(" method=").append(quoted(orDash(finding.methodName())))
                    .append(" line=").append(finding.lineNumber() > 0 ? finding.lineNumber() : "-")
                    .append(" status=").append(finding.status())
                    .append(" mutator=").append(quoted(orDash(shortMutator(finding.mutator()))))
                    .append(" testsRun=").append(finding.testsRun() >= 0 ? finding.testsRun() : "-")
                    .append(" description=").append(quoted(orDash(finding.description())))
                    .append(" action=").append(quoted(finding.action()))
                    .append(System.lineSeparator());
        }
        if (omittedCount > 0) {
            builder.append("MUTATION_FINDINGS_OMITTED: count=")
                    .append(omittedCount)
                    .append(System.lineSeparator());
        }
    }

    private void appendBuildIssues(StringBuilder builder, List<VerificationIssue> buildIssues) {
        if (buildIssues.isEmpty()) {
            builder.append("BUILD_ISSUE: NONE").append(System.lineSeparator());
            return;
        }
        for (VerificationIssue issue : buildIssues) {
            builder.append("BUILD_ISSUE: ")
                    .append("reason=").append(quoted(issue.reason()))
                    .append(System.lineSeparator());
            builder.append("BUILD_ACTION: ")
                    .append("action=").append(quoted(issue.action()))
                    .append(System.lineSeparator());
        }
    }

    private String nextStep(VerificationResult result) {
        if (!result.buildIssues().isEmpty()) {
            return "Resolve the build issues first, then rerun `jaipilot verify`.";
        }
        if (!result.coverageFindings().isEmpty() || !result.mutationFindings().isEmpty()) {
            return "Implement the listed test improvements, then rerun `jaipilot verify`.";
        }
        return "Use this result as a release gate or agent checkpoint.";
    }

    private static String formatNumbers(List<Integer> numbers) {
        if (numbers == null || numbers.isEmpty()) {
            return "-";
        }
        StringJoiner joiner = new StringJoiner(",");
        for (Integer number : numbers) {
            joiner.add(String.valueOf(number));
        }
        return joiner.toString();
    }

    private static String formatStatusCounts(Map<String, Integer> statusCounts) {
        if (statusCounts == null || statusCounts.isEmpty()) {
            return "-";
        }
        StringJoiner joiner = new StringJoiner(",");
        statusCounts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .forEach(entry -> joiner.add(entry.getKey() + ":" + entry.getValue()));
        return joiner.toString();
    }

    private static String displayPath(Path path) {
        if (path == null) {
            return "-";
        }
        String value = path.toString();
        return value.isBlank() ? "." : value;
    }

    private static String shortMutator(String mutator) {
        int lastDot = mutator.lastIndexOf('.');
        return lastDot >= 0 ? mutator.substring(lastDot + 1) : mutator;
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String quoted(String value) {
        return "\"" + sanitize(value) + "\"";
    }

    private static String sanitize(String value) {
        return value
                .replace("\"", "'")
                .replace(System.lineSeparator(), " ")
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }
}
