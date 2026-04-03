package com.jaipilot.cli.classpath;

import java.nio.file.Path;
import java.util.List;

public record ResolvedClasspath(
        BuildToolType buildToolType,
        Path projectRoot,
        Path moduleRoot,
        List<Path> classpathEntries,
        List<Path> mainOutputDirs,
        List<Path> testOutputDirs,
        List<Path> mainSourceRoots,
        List<Path> testSourceRoots,
        String fingerprint
) {

    public ResolvedClasspath {
        classpathEntries = normalizeList(classpathEntries);
        mainOutputDirs = normalizeList(mainOutputDirs);
        testOutputDirs = normalizeList(testOutputDirs);
        mainSourceRoots = normalizeList(mainSourceRoots);
        testSourceRoots = normalizeList(testSourceRoots);
    }

    private static List<Path> normalizeList(List<Path> paths) {
        if (paths == null || paths.isEmpty()) {
            return List.of();
        }
        return paths.stream()
                .filter(path -> path != null && !path.toString().isBlank())
                .map(path -> path.toAbsolutePath().normalize())
                .distinct()
                .toList();
    }
}
