package com.jaipilot.cli.report.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public record JacocoReport(
        CoverageCounter lineCounter,
        CoverageCounter branchCounter,
        CoverageCounter instructionCounter,
        List<JacocoSourceFileCoverage> sourceFiles
) {
    public CoverageCounter counter(CoverageMetric metric) {
        return switch (metric) {
            case LINE -> lineCounter;
            case BRANCH -> branchCounter;
            case INSTRUCTION -> instructionCounter;
        };
    }

    public int moduleCount() {
        return (int) sourceFiles.stream()
                .map(JacocoSourceFileCoverage::modulePath)
                .distinct()
                .count();
    }

    public int sourceFileCount() {
        return sourceFiles.size();
    }

    public int classCount() {
        Set<String> classNames = sourceFiles.stream()
                .flatMap(sourceFile -> sourceFile.methods().stream())
                .map(JacocoMethodCoverage::className)
                .collect(Collectors.toSet());
        return classNames.size();
    }

    public int methodCount() {
        return sourceFiles.stream()
                .mapToInt(sourceFile -> sourceFile.methods().size())
                .sum();
    }
}
