package com.jaipilot.cli.classpath;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Optional;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class MavenCoordinateExtractor {

    Optional<MavenCoordinates> extract(Path jarPath) {
        if (jarPath == null) {
            return Optional.empty();
        }

        Optional<MavenCoordinates> fromMetadata = fromPomProperties(jarPath);
        if (fromMetadata.isPresent()) {
            return fromMetadata;
        }
        return fromLocalRepositoryPath(jarPath);
    }

    private Optional<MavenCoordinates> fromPomProperties(Path jarPath) {
        try (ZipFile zipFile = new ZipFile(jarPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (!entryName.startsWith("META-INF/maven/") || !entryName.endsWith("/pom.properties")) {
                    continue;
                }
                try (InputStream inputStream = zipFile.getInputStream(entry)) {
                    Properties properties = new Properties();
                    properties.load(inputStream);
                    String groupId = properties.getProperty("groupId");
                    String artifactId = properties.getProperty("artifactId");
                    String version = properties.getProperty("version");
                    if (isPresent(groupId) && isPresent(artifactId) && isPresent(version)) {
                        return Optional.of(new MavenCoordinates(groupId.trim(), artifactId.trim(), version.trim()));
                    }
                }
            }
        } catch (IOException ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private Optional<MavenCoordinates> fromLocalRepositoryPath(Path jarPath) {
        Path normalized = jarPath.toAbsolutePath().normalize();
        int count = normalized.getNameCount();
        if (count < 4) {
            return Optional.empty();
        }

        int repositoryIndex = -1;
        for (int index = 0; index < count; index++) {
            if ("repository".equals(normalized.getName(index).toString())) {
                repositoryIndex = index;
                break;
            }
        }
        if (repositoryIndex < 0 || count - repositoryIndex < 4) {
            return Optional.empty();
        }

        String version = normalized.getName(count - 2).toString();
        String artifactId = normalized.getName(count - 3).toString();
        if (!isPresent(version) || !isPresent(artifactId)) {
            return Optional.empty();
        }

        if (count - repositoryIndex < 5) {
            return Optional.empty();
        }

        StringBuilder groupBuilder = new StringBuilder();
        for (int index = repositoryIndex + 1; index <= count - 4; index++) {
            if (groupBuilder.length() > 0) {
                groupBuilder.append('.');
            }
            groupBuilder.append(normalized.getName(index));
        }

        String groupId = groupBuilder.toString();
        if (!isPresent(groupId)) {
            return Optional.empty();
        }

        return Optional.of(new MavenCoordinates(groupId, artifactId, version));
    }

    private boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
