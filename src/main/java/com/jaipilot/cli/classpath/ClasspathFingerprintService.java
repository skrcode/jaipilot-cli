package com.jaipilot.cli.classpath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class ClasspathFingerprintService {

    String fingerprint(
            BuildToolType buildToolType,
            Path projectRoot,
            Path moduleRoot,
            Path executable,
            ResolutionOptions options
    ) {
        Path normalizedProjectRoot = projectRoot.toAbsolutePath().normalize();
        Path normalizedModuleRoot = moduleRoot.toAbsolutePath().normalize();
        ResolutionOptions normalizedOptions = options == null ? ResolutionOptions.defaults() : options;

        List<String> lines = new ArrayList<>();
        lines.add("tool=" + buildToolType);
        lines.add("projectRoot=" + FingerprintUtils.normalize(normalizedProjectRoot));
        lines.add("moduleRoot=" + FingerprintUtils.normalize(normalizedModuleRoot));
        lines.add("executable=" + FingerprintUtils.normalize(executable));
        lines.add("java.home=" + System.getProperty("java.home", ""));
        lines.add("java.version=" + System.getProperty("java.version", ""));
        for (int index = 0; index < normalizedOptions.buildArgs().size(); index++) {
            lines.add("arg[" + index + "]=" + normalizedOptions.buildArgs().get(index));
        }

        if (buildToolType == BuildToolType.MAVEN) {
            collectMavenInputs(normalizedProjectRoot, normalizedModuleRoot).forEach(lines::add);
        } else {
            collectGradleInputs(normalizedProjectRoot, normalizedModuleRoot).forEach(lines::add);
        }

        return FingerprintUtils.sha256Hex(lines);
    }

    private List<String> collectMavenInputs(Path projectRoot, Path moduleRoot) {
        List<String> inputs = new ArrayList<>();
        Path current = moduleRoot;
        while (current != null && current.startsWith(projectRoot)) {
            Path pom = current.resolve("pom.xml");
            inputs.add(fileDescriptor(projectRoot, pom));
            if (current.equals(projectRoot)) {
                break;
            }
            current = current.getParent();
        }

        Path mavenDir = projectRoot.resolve(".mvn");
        for (Path file : FingerprintUtils.sortedFiles(mavenDir)) {
            inputs.add(fileDescriptor(projectRoot, file));
        }

        return inputs.stream()
                .sorted(String::compareTo)
                .toList();
    }

    private List<String> collectGradleInputs(Path projectRoot, Path moduleRoot) {
        List<String> inputs = new ArrayList<>();
        for (String settingsFile : List.of("settings.gradle", "settings.gradle.kts")) {
            inputs.add(fileDescriptor(projectRoot, projectRoot.resolve(settingsFile)));
        }
        for (String buildFile : List.of("build.gradle", "build.gradle.kts")) {
            inputs.add(fileDescriptor(projectRoot, projectRoot.resolve(buildFile)));
        }
        inputs.add(fileDescriptor(projectRoot, projectRoot.resolve("gradle.properties")));
        inputs.add(fileDescriptor(projectRoot, projectRoot.resolve("gradle/libs.versions.toml")));

        Path current = moduleRoot;
        while (current != null && current.startsWith(projectRoot)) {
            for (String buildFile : List.of("build.gradle", "build.gradle.kts", "gradle.properties")) {
                inputs.add(fileDescriptor(projectRoot, current.resolve(buildFile)));
            }
            if (current.equals(projectRoot)) {
                break;
            }
            current = current.getParent();
        }

        Path gradleDir = projectRoot.resolve("gradle");
        for (Path file : FingerprintUtils.sortedFiles(gradleDir)) {
            String fileName = file.getFileName() == null ? "" : file.getFileName().toString();
            if ("libs.versions.toml".equals(fileName) || fileName.endsWith(".properties") || fileName.endsWith(".gradle")
                    || fileName.endsWith(".gradle.kts")) {
                inputs.add(fileDescriptor(projectRoot, file));
            }
        }

        return inputs.stream()
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private String fileDescriptor(Path projectRoot, Path file) {
        Path normalizedProjectRoot = projectRoot.toAbsolutePath().normalize();
        Path normalizedFile = file.toAbsolutePath().normalize();
        String relative = normalizedFile.startsWith(normalizedProjectRoot)
                ? normalizedProjectRoot.relativize(normalizedFile).toString().replace('\\', '/')
                : normalizedFile.toString().replace('\\', '/');
        String exists = Files.isRegularFile(normalizedFile) ? "present" : "missing";
        return relative.toLowerCase(Locale.ROOT) + "=" + exists + ":" + FingerprintUtils.fileHash(normalizedFile);
    }
}
