package com.jaipilot.cli.classpath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class BuildToolDetector {

    private static final List<String> GRADLE_MARKERS = List.of(
            "settings.gradle",
            "settings.gradle.kts",
            "build.gradle",
            "build.gradle.kts"
    );

    public BuildToolType detectBuildTool(Path projectRoot) {
        return detectBuildTool(projectRoot, projectRoot);
    }

    public BuildToolType detectBuildTool(Path projectRoot, Path moduleRoot) {
        Path normalizedProjectRoot = normalizeDirectory(projectRoot, "projectRoot");
        Path normalizedModuleRoot = normalizeDirectory(moduleRoot, "moduleRoot");
        if (!normalizedModuleRoot.startsWith(normalizedProjectRoot)) {
            throw new ClasspathResolutionException(new ResolutionFailure(
                    ResolutionFailureCategory.UNSUPPORTED_BUILD_TOOL,
                    null,
                    normalizedModuleRoot,
                    "build-tool-detection",
                    "Module root must be inside project root."
            ));
        }

        boolean moduleHasPom = Files.isRegularFile(normalizedModuleRoot.resolve("pom.xml"));
        boolean moduleHasGradle = hasGradleMarker(normalizedModuleRoot);
        if (moduleHasPom && moduleHasGradle) {
            throw new ClasspathResolutionException(new ResolutionFailure(
                    ResolutionFailureCategory.UNSUPPORTED_BUILD_TOOL,
                    null,
                    normalizedModuleRoot,
                    "build-tool-detection",
                    "Both Maven and Gradle build files exist at module root."
            ));
        }
        if (moduleHasPom) {
            return BuildToolType.MAVEN;
        }
        if (moduleHasGradle) {
            return BuildToolType.GRADLE;
        }

        boolean mavenDetected = hasPomInAncestors(normalizedModuleRoot, normalizedProjectRoot);
        boolean gradleDetected = hasGradleInAncestors(normalizedModuleRoot, normalizedProjectRoot);

        if (mavenDetected && gradleDetected) {
            throw new ClasspathResolutionException(new ResolutionFailure(
                    ResolutionFailureCategory.UNSUPPORTED_BUILD_TOOL,
                    null,
                    normalizedModuleRoot,
                    "build-tool-detection",
                    "Both Maven and Gradle markers exist in ancestor hierarchy; pick a module root with one tool."
            ));
        }
        if (mavenDetected) {
            return BuildToolType.MAVEN;
        }
        if (gradleDetected) {
            return BuildToolType.GRADLE;
        }

        throw new ClasspathResolutionException(new ResolutionFailure(
                ResolutionFailureCategory.UNSUPPORTED_BUILD_TOOL,
                null,
                normalizedModuleRoot,
                "build-tool-detection",
                "No supported build files were found."
        ));
    }

    private static Path normalizeDirectory(Path path, String label) {
        if (path == null) {
            throw new IllegalArgumentException(label + " must not be null");
        }
        return path.toAbsolutePath().normalize();
    }

    private boolean hasPomInAncestors(Path moduleRoot, Path projectRoot) {
        Path current = moduleRoot;
        while (current != null && current.startsWith(projectRoot)) {
            if (Files.isRegularFile(current.resolve("pom.xml"))) {
                return true;
            }
            if (current.equals(projectRoot)) {
                break;
            }
            current = current.getParent();
        }
        return false;
    }

    private boolean hasGradleInAncestors(Path moduleRoot, Path projectRoot) {
        Path current = moduleRoot;
        while (current != null && current.startsWith(projectRoot)) {
            if (hasGradleMarker(current)) {
                return true;
            }
            if (current.equals(projectRoot)) {
                break;
            }
            current = current.getParent();
        }
        return false;
    }

    private boolean hasGradleMarker(Path directory) {
        return GRADLE_MARKERS.stream()
                .map(directory::resolve)
                .anyMatch(Files::isRegularFile);
    }
}
