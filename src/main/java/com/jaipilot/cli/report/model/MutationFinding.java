package com.jaipilot.cli.report.model;

import java.nio.file.Path;

public record MutationFinding(
        Path modulePath,
        Path sourceFilePath,
        String className,
        String methodName,
        int lineNumber,
        String mutator,
        String status,
        String description,
        int testsRun,
        String action
) {
}
