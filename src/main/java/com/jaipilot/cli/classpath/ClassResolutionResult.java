package com.jaipilot.cli.classpath;

import java.nio.file.Path;
import java.util.Optional;

public record ClassResolutionResult(
        String fqcn,
        LocationKind kind,
        Path containerPath,
        String classEntryPath,
        Optional<Path> mappedSourcePath,
        Optional<MavenCoordinates> externalCoordinates
) {

    public ClassResolutionResult {
        mappedSourcePath = mappedSourcePath == null ? Optional.empty() : mappedSourcePath.map(path -> path.toAbsolutePath().normalize());
        externalCoordinates = externalCoordinates == null ? Optional.empty() : externalCoordinates;
    }
}
