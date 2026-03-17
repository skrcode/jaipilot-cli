package com.jaipilot.cli.report.model;

public record JacocoMethodCoverage(
        String className,
        String methodName,
        String descriptor,
        int startLine,
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

    public String displayName() {
        return switch (methodName) {
            case "<init>" -> className.substring(className.lastIndexOf('.') + 1);
            case "<clinit>" -> className.substring(className.lastIndexOf('.') + 1) + ".<clinit>";
            default -> methodName;
        };
    }
}
