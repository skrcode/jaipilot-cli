package com.jaipilot.cli.classpath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildToolDetectorTest {

    @TempDir
    Path tempDir;

    private final BuildToolDetector detector = new BuildToolDetector();

    @Test
    void detectsMavenFromAncestorPom() throws Exception {
        Path projectRoot = tempDir.resolve("repo");
        Path moduleRoot = projectRoot.resolve("services/users");
        Files.createDirectories(moduleRoot);
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");

        assertEquals(BuildToolType.MAVEN, detector.detectBuildTool(projectRoot, moduleRoot));
    }

    @Test
    void detectsGradleFromModuleMarkerWhenAncestorHasPom() throws Exception {
        Path projectRoot = tempDir.resolve("repo");
        Path moduleRoot = projectRoot.resolve("apps/api");
        Files.createDirectories(moduleRoot);
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");
        Files.writeString(moduleRoot.resolve("build.gradle"), "plugins { id 'java' }");

        assertEquals(BuildToolType.GRADLE, detector.detectBuildTool(projectRoot, moduleRoot));
    }

    @Test
    void failsOnAmbiguousAncestorMarkersWithoutModuleMarker() throws Exception {
        Path projectRoot = tempDir.resolve("repo");
        Path moduleRoot = projectRoot.resolve("shared/module");
        Files.createDirectories(moduleRoot);
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");
        Files.writeString(projectRoot.resolve("settings.gradle"), "rootProject.name='repo'");

        ClasspathResolutionException exception = assertThrows(
                ClasspathResolutionException.class,
                () -> detector.detectBuildTool(projectRoot, moduleRoot)
        );

        assertEquals(ResolutionFailureCategory.UNSUPPORTED_BUILD_TOOL, exception.failure().category());
    }
}
