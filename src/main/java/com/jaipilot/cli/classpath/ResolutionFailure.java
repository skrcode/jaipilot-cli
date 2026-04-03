package com.jaipilot.cli.classpath;

import java.nio.file.Path;

public record ResolutionFailure(
        ResolutionFailureCategory category,
        BuildToolType buildTool,
        Path moduleRoot,
        String actionSummary,
        String outputSnippet
) {

    public ResolutionFailure {
        moduleRoot = moduleRoot == null ? null : moduleRoot.toAbsolutePath().normalize();
        actionSummary = actionSummary == null ? "" : actionSummary;
        outputSnippet = outputSnippet == null ? "" : outputSnippet;
    }
}
