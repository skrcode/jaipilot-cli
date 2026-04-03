package com.jaipilot.cli.classpath;

import java.nio.file.Path;

public record ResolvedSource(
        String fqcn,
        SourceOrigin origin,
        Path sourceContainer,
        String sourceText
) {

    public ResolvedSource {
        sourceContainer = sourceContainer == null ? null : sourceContainer.toAbsolutePath().normalize();
        sourceText = sourceText == null ? "" : sourceText;
    }
}
