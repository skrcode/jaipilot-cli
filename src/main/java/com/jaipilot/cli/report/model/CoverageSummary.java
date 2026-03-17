package com.jaipilot.cli.report.model;

public record CoverageSummary(
        int moduleCount,
        int sourceFileCount,
        int classCount,
        int methodCount,
        double lineCoverage,
        double branchCoverage,
        double instructionCoverage
) {
}
