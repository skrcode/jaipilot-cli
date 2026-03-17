package com.jaipilot.cli.report.model;

public record ClassCoverage(
        String className,
        CoverageCounter lineCounter,
        CoverageCounter branchCounter,
        CoverageCounter instructionCounter
) {
    public CoverageCounter counter(CoverageMetric metric) {
        return switch (metric) {
            case LINE -> lineCounter;
            case BRANCH -> branchCounter;
            case INSTRUCTION -> instructionCounter;
        };
    }
}
