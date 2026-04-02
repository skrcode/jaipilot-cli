package com.jaipilot.cli.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenCommandBuilderTest {

    private final MavenCommandBuilder commandBuilder = new MavenCommandBuilder();

    @TempDir
    Path tempDir;

    @Test
    void buildsTestCompileCommand() {
        List<String> command = commandBuilder.buildTestCompile(
                Path.of("/tmp/project"),
                Path.of("custom-mvn"),
                List.of("-DskipITs")
        );

        assertEquals("custom-mvn", command.get(0));
        assertTrue(command.contains("-B"));
        assertTrue(command.contains("-ntp"));
        assertTrue(command.contains("-DskipITs"));
        assertTrue(command.contains("-DskipTests"));
        assertTrue(command.contains("test-compile"));
    }

    @Test
    void buildsSingleTestExecutionCommand() {
        List<String> command = commandBuilder.buildSingleTestExecution(
                Path.of("/tmp/project"),
                Path.of("custom-mvn"),
                List.of("-DskipITs"),
                "com.example.CrashControllerTest"
        );

        assertEquals("custom-mvn", command.get(0));
        assertTrue(command.contains("-DskipITs"));
        assertTrue(command.contains("-Dtest=com.example.CrashControllerTest"));
        assertTrue(command.contains("-Dsurefire.failIfNoSpecifiedTests=false"));
        assertTrue(command.contains("test"));
    }

    @Test
    void buildsDependencySourcesDownloadCommand() {
        List<String> command = commandBuilder.buildDependencySourcesDownload(
                Path.of("/tmp/project"),
                Path.of("custom-mvn"),
                List.of("-DskipITs")
        );

        assertEquals("custom-mvn", command.get(0));
        assertTrue(command.contains("-B"));
        assertTrue(command.contains("-ntp"));
        assertTrue(command.contains("-DskipITs"));
        assertTrue(command.contains("-DskipTests"));
        assertTrue(command.contains("dependency:sources"));
    }

    @Test
    void resolvesWrapperFromAncestorProjectRoot() throws Exception {
        Path root = tempDir.resolve("repo");
        Path moduleDir = root.resolve("module-a");
        Files.createDirectories(moduleDir);
        Path wrapper = root.resolve("mvnw");
        Path wrapperProperties = root.resolve(".mvn/wrapper/maven-wrapper.properties");
        Files.createDirectories(wrapperProperties.getParent());
        Files.writeString(wrapper, "#!/usr/bin/env sh\n");
        Files.writeString(wrapperProperties, "distributionUrl=https://repo.maven.apache.org/maven2\n");

        assertEquals(wrapper.toString(), commandBuilder.resolveMavenExecutable(moduleDir, null));
    }

    @Test
    void fallsBackToSystemMavenWhenWrapperPropertiesAreMissing() throws Exception {
        Path root = tempDir.resolve("repo");
        Path moduleDir = root.resolve("module-a");
        Files.createDirectories(moduleDir);
        Files.writeString(root.resolve("mvnw"), "#!/usr/bin/env sh\n");

        assertEquals(systemMavenExecutable(), commandBuilder.resolveMavenExecutable(moduleDir, null));
    }

    @Test
    void fallsBackToSystemMavenWhenExplicitWrapperPropertiesAreMissing() throws Exception {
        Path root = tempDir.resolve("repo");
        Files.createDirectories(root);
        Files.writeString(root.resolve("mvnw"), "#!/usr/bin/env sh\n");

        assertEquals(systemMavenExecutable(), commandBuilder.resolveMavenExecutable(root, Path.of("mvnw")));
    }

    @Test
    void keepsExplicitWrapperWhenWrapperPropertiesExist() throws Exception {
        Path root = tempDir.resolve("repo");
        Path wrapperProperties = root.resolve(".mvn/wrapper/maven-wrapper.properties");
        Files.createDirectories(wrapperProperties.getParent());
        Files.writeString(root.resolve("mvnw"), "#!/usr/bin/env sh\n");
        Files.writeString(wrapperProperties, "distributionUrl=https://repo.maven.apache.org/maven2\n");

        assertEquals(
                root.resolve("mvnw").toString(),
                commandBuilder.resolveMavenExecutable(root, Path.of("mvnw"))
        );
    }

    private String systemMavenExecutable() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win")
                ? "mvn.cmd"
                : "mvn";
    }
}
