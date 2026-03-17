package com.jaipilot.cli.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;

public final class SourcePathResolver {

    private SourcePathResolver() {
    }

    public static Path resolveMainSource(Path moduleRoot, String packageName, String sourceFileName) {
        Path packagePath = packageName == null || packageName.isBlank()
                ? Path.of("")
                : Path.of(packageName.replace('.', '/'));
        Path conventionalPath = moduleRoot.resolve("src/main/java").resolve(packagePath).resolve(sourceFileName).normalize();
        if (Files.isRegularFile(conventionalPath)) {
            return conventionalPath;
        }

        Path mainSourceRoot = moduleRoot.resolve("src/main/java");
        if (!Files.isDirectory(mainSourceRoot)) {
            return conventionalPath;
        }

        try (var paths = Files.walk(mainSourceRoot)) {
            Optional<Path> match = paths
                    .filter(path -> path.getFileName() != null && sourceFileName.equals(path.getFileName().toString()))
                    .sorted(Comparator.comparingInt(Path::getNameCount))
                    .findFirst();
            return match.orElse(conventionalPath);
        } catch (IOException exception) {
            return conventionalPath;
        }
    }
}
