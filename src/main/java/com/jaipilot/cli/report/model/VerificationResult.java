package com.jaipilot.cli.report.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record VerificationResult(
        Path projectRoot,
        boolean successful,
        VerificationThresholds thresholds,
        List<CheckResult> checks,
        CoverageSummary coverageSummary,
        Map<CoverageMetric, List<CoverageFinding>> coverageFindings,
        Map<CoverageMetric, Integer> omittedCoverageFindings,
        List<VerificationIssue> buildIssues,
        Path debugWorkspace
) {
}
