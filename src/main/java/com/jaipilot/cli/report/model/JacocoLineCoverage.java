package com.jaipilot.cli.report.model;

public record JacocoLineCoverage(
        int lineNumber,
        int missedInstructions,
        int coveredInstructions,
        int missedBranches,
        int coveredBranches
) {
    public boolean uncovered() {
        return coveredInstructions == 0 && missedInstructions > 0;
    }

    public boolean branchDeficit() {
        return missedBranches > 0;
    }
}
