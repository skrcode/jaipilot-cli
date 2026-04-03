package com.jaipilot.cli.classpath;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

final class ResolvedClasspathCache {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Path cacheRoot;

    ResolvedClasspathCache(Path cacheRoot) {
        this.cacheRoot = cacheRoot.toAbsolutePath().normalize();
    }

    Optional<ResolvedClasspath> read(String fingerprint) {
        Path cacheFile = cacheFile(fingerprint);
        if (!Files.isRegularFile(cacheFile)) {
            return Optional.empty();
        }
        try {
            CacheRecord record = OBJECT_MAPPER.readValue(cacheFile.toFile(), CacheRecord.class);
            return Optional.of(record.toResolvedClasspath());
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    void write(ResolvedClasspath classpath) {
        try {
            Files.createDirectories(cacheRoot);
            OBJECT_MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValue(cacheFile(classpath.fingerprint()).toFile(), CacheRecord.from(classpath));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write classpath cache for " + classpath.fingerprint(), exception);
        }
    }

    void evict(String fingerprint) {
        Path cacheFile = cacheFile(fingerprint);
        try {
            Files.deleteIfExists(cacheFile);
        } catch (IOException ignored) {
            // Best-effort eviction.
        }
    }

    private Path cacheFile(String fingerprint) {
        return cacheRoot.resolve(fingerprint + ".json");
    }

    private record CacheRecord(
            String buildToolType,
            String projectRoot,
            String moduleRoot,
            List<String> classpathEntries,
            List<String> mainOutputDirs,
            List<String> testOutputDirs,
            List<String> mainSourceRoots,
            List<String> testSourceRoots,
            String fingerprint
    ) {

        static CacheRecord from(ResolvedClasspath classpath) {
            return new CacheRecord(
                    classpath.buildToolType().name(),
                    classpath.projectRoot().toString(),
                    classpath.moduleRoot().toString(),
                    toStrings(classpath.classpathEntries()),
                    toStrings(classpath.mainOutputDirs()),
                    toStrings(classpath.testOutputDirs()),
                    toStrings(classpath.mainSourceRoots()),
                    toStrings(classpath.testSourceRoots()),
                    classpath.fingerprint()
            );
        }

        ResolvedClasspath toResolvedClasspath() {
            return new ResolvedClasspath(
                    BuildToolType.valueOf(buildToolType),
                    Path.of(projectRoot),
                    Path.of(moduleRoot),
                    toPaths(classpathEntries),
                    toPaths(mainOutputDirs),
                    toPaths(testOutputDirs),
                    toPaths(mainSourceRoots),
                    toPaths(testSourceRoots),
                    fingerprint
            );
        }

        private static List<String> toStrings(List<Path> paths) {
            if (paths == null || paths.isEmpty()) {
                return List.of();
            }
            return paths.stream().map(path -> path.toAbsolutePath().normalize().toString()).toList();
        }

        private static List<Path> toPaths(List<String> values) {
            if (values == null || values.isEmpty()) {
                return List.of();
            }
            return values.stream().map(Path::of).toList();
        }
    }
}
