package com.jaipilot.cli.report.model;

import java.nio.file.Path;
import java.util.List;

public record CoverageFinding(
        CoverageMetric metric,
        Path modulePath,
        Path sourceFilePath,
        String className,
        String methodName,
        double actual,
        List<Integer> uncoveredLines,
        List<Integer> branchDeficitLines,
        String action
) {
}
