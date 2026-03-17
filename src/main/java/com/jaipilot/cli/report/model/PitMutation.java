package com.jaipilot.cli.report.model;

import java.nio.file.Path;

public record PitMutation(
        Path modulePath,
        Path sourceFilePath,
        String className,
        String methodName,
        String methodDescription,
        int lineNumber,
        String mutator,
        String status,
        String description,
        int testsRun,
        boolean detected
) {
    public String shortMutator() {
        int lastDot = mutator.lastIndexOf('.');
        return lastDot >= 0 ? mutator.substring(lastDot + 1) : mutator;
    }

    public boolean actionable() {
        return !"KILLED".equalsIgnoreCase(status);
    }
}
