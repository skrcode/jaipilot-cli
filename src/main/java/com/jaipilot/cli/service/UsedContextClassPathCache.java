package com.jaipilot.cli.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class UsedContextClassPathCache {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, List<String>>> CACHE_TYPE =
            new TypeReference<>() { };

    private final Path cacheFile;

    public UsedContextClassPathCache() {
        this(Path.of(System.getProperty("java.io.tmpdir"), "jaipilot-cli", "used-context-class-paths.json"));
    }

    UsedContextClassPathCache(Path cacheFile) {
        this.cacheFile = cacheFile.toAbsolutePath().normalize();
    }

    public List<String> read(Path absoluteClassPath) {
        List<String> values = readAll().get(cacheKey(absoluteClassPath));
        if (values == null) {
            return List.of();
        }
        return List.copyOf(values);
    }

    public void write(Path absoluteClassPath, List<String> usedContextClassPaths) {
        Map<String, List<String>> entries = new LinkedHashMap<>();
        entries.put(cacheKey(absoluteClassPath), normalizeValues(usedContextClassPaths));
        writeAll(entries);
    }

    private Map<String, List<String>> readAll() {
        if (!Files.isRegularFile(cacheFile)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, List<String>> entries = OBJECT_MAPPER.readValue(cacheFile.toFile(), CACHE_TYPE);
            return entries == null ? new LinkedHashMap<>() : new LinkedHashMap<>(entries);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read used context cache " + cacheFile, exception);
        }
    }

    private void writeAll(Map<String, List<String>> entries) {
        try {
            Path parent = cacheFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(cacheFile.toFile(), entries);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write used context cache " + cacheFile, exception);
        }
    }

    private String cacheKey(Path absoluteClassPath) {
        return absoluteClassPath.toAbsolutePath().normalize().toString();
    }

    private List<String> normalizeValues(List<String> usedContextClassPaths) {
        if (usedContextClassPaths == null || usedContextClassPaths.isEmpty()) {
            return List.of();
        }
        return usedContextClassPaths.stream()
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }
}
