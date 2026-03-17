package com.jaipilot.cli.report.model;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public record JacocoSourceFileCoverage(
        Path modulePath,
        Path sourceFilePath,
        String packageName,
        String sourceFileName,
        CoverageCounter lineCounter,
        CoverageCounter branchCounter,
        CoverageCounter instructionCounter,
        List<JacocoLineCoverage> lines,
        List<JacocoMethodCoverage> methods
) {
    public CoverageCounter counter(CoverageMetric metric) {
        return switch (metric) {
            case LINE -> lineCounter;
            case BRANCH -> branchCounter;
            case INSTRUCTION -> instructionCounter;
        };
    }

    public List<Integer> uncoveredLines(int limit) {
        return lines.stream()
                .filter(JacocoLineCoverage::uncovered)
                .map(JacocoLineCoverage::lineNumber)
                .limit(limit)
                .collect(Collectors.toList());
    }

    public List<Integer> branchDeficitLines(int limit) {
        return lines.stream()
                .filter(JacocoLineCoverage::branchDeficit)
                .map(JacocoLineCoverage::lineNumber)
                .limit(limit)
                .collect(Collectors.toList());
    }

    public JacocoMethodCoverage lowestCoverageMethod(CoverageMetric metric) {
        return methods.stream()
                .filter(method -> method.counter(metric).total() > 0)
                .sorted(Comparator.comparingDouble((JacocoMethodCoverage method) -> method.counter(metric).percentage())
                        .thenComparingInt(JacocoMethodCoverage::startLine))
                .findFirst()
                .orElse(null);
    }
}
