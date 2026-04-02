package com.jaipilot.cli.bootstrap;

import java.nio.file.Path;

public record ReactorModule(
        String artifactId,
        Path originalPomPath,
        Path relativePomPath,
        Path originalModuleDir,
        Path relativeModuleDir,
        String packaging,
        boolean javaModule
) {
    public boolean rootModule() {
        return relativeModuleDir.toString().isBlank();
    }

    public boolean aggregateModule() {
        return "pom".equalsIgnoreCase(packaging);
    }

    public String moduleLabel() {
        return rootModule() ? "." : relativeModuleDir.toString();
    }
}
