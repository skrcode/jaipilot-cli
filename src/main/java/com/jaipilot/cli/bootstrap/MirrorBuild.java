package com.jaipilot.cli.bootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public record MirrorBuild(
        Path originalProjectRoot,
        Path tempProjectRoot,
        Path buildPomPath,
        List<ReactorModule> modules,
        boolean runAggregateCoverage
) {
    public void cleanup() throws IOException {
        if (!Files.exists(tempProjectRoot)) {
            return;
        }

        try (var paths = Files.walk(tempProjectRoot)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            throw new IllegalStateException("Failed to delete " + path, exception);
                        }
                    });
        }
    }
}
