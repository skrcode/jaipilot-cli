package com.jaipilot.cli.report.model;

public record VerificationThresholds(
        double lineCoverageThreshold,
        double branchCoverageThreshold,
        double instructionCoverageThreshold,
        double mutationThreshold
) {
}
