package com.jaipilot.cli.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GradleCommandBuilderTest {

    private final GradleCommandBuilder commandBuilder = new GradleCommandBuilder();

    @TempDir
    Path tempDir;

    @Test
    void buildsTestCompileCommand() {
        List<String> command = commandBuilder.buildTestCompile(
                Path.of("/tmp/project"),
                Path.of("custom-gradle"),
                List.of("--stacktrace")
        );

        assertEquals("custom-gradle", command.get(0));
        assertTrue(command.contains("--no-daemon"));
        assertTrue(command.contains("--console=plain"));
        assertTrue(command.contains("--stacktrace"));
        assertTrue(command.contains("testClasses"));
    }

    @Test
    void buildsModuleScopedTestCompileCommand() {
        List<String> command = commandBuilder.buildTestCompile(
                Path.of("/tmp/project"),
                Path.of("custom-gradle"),
                List.of("--stacktrace"),
                ":clients"
        );

        assertTrue(command.contains(":clients:testClasses"));
    }

    @Test
    void buildsSingleTestExecutionCommand() {
        List<String> command = commandBuilder.buildSingleTestExecution(
                Path.of("/tmp/project"),
                Path.of("custom-gradle"),
                List.of("--stacktrace"),
                "com.example.CrashControllerTest"
        );

        assertEquals("custom-gradle", command.get(0));
        assertTrue(command.contains("--stacktrace"));
        assertTrue(command.contains("test"));
        assertTrue(command.contains("--tests"));
        assertTrue(command.contains("com.example.CrashControllerTest"));
    }

    @Test
    void buildsCodebaseRulesValidationCommand() {
        List<String> command = commandBuilder.buildCodebaseRulesValidation(
                Path.of("/tmp/project"),
                Path.of("custom-gradle"),
                List.of("--stacktrace")
        );

        assertEquals("custom-gradle", command.get(0));
        assertTrue(command.contains("--no-daemon"));
        assertTrue(command.contains("--console=plain"));
        assertTrue(command.contains("--stacktrace"));
        assertTrue(command.contains("check"));
        assertTrue(command.contains("-x"));
        assertTrue(command.contains("test"));
    }

    @Test
    void buildsModuleScopedSingleTestExecutionCommand() {
        List<String> command = commandBuilder.buildSingleTestExecution(
                Path.of("/tmp/project"),
                Path.of("custom-gradle"),
                List.of("--stacktrace"),
                "com.example.CrashControllerTest",
                ":clients"
        );

        assertTrue(command.contains(":clients:test"));
        assertTrue(command.contains("com.example.CrashControllerTest"));
    }

    @Test
    void buildsModuleScopedCodebaseRulesValidationCommand() {
        List<String> command = commandBuilder.buildCodebaseRulesValidation(
                Path.of("/tmp/project"),
                Path.of("custom-gradle"),
                List.of("--stacktrace"),
                ":clients"
        );

        assertTrue(command.contains(":clients:check"));
        assertTrue(command.contains("-x"));
        assertTrue(command.contains(":clients:test"));
    }

    @Test
    void buildsDependencySourcesDownloadCommand() {
        List<String> command = commandBuilder.buildDependencySourcesDownload(
                Path.of("/tmp/project"),
                Path.of("custom-gradle"),
                List.of("--stacktrace"),
                Path.of("/tmp/jaipilot-init.gradle")
        );

        assertEquals("custom-gradle", command.get(0));
        assertTrue(command.contains("--no-daemon"));
        assertTrue(command.contains("--console=plain"));
        assertTrue(command.contains("--stacktrace"));
        assertTrue(command.contains("-I"));
        assertTrue(command.contains("/tmp/jaipilot-init.gradle"));
        assertTrue(command.contains("jaipilotDownloadSources"));
    }

    @Test
    void buildsSingleTestCoverageCommand() {
        List<String> command = commandBuilder.buildSingleTestCoverage(
                Path.of("/tmp/project"),
                Path.of("custom-gradle"),
                List.of("--stacktrace"),
                "com.example.CrashControllerTest",
                ":clients",
                Path.of("/tmp/jaipilot-jacoco-init.gradle")
        );

        assertEquals("custom-gradle", command.get(0));
        assertTrue(command.contains("--no-daemon"));
        assertTrue(command.contains("--console=plain"));
        assertTrue(command.contains("--stacktrace"));
        assertTrue(command.contains("-I"));
        assertTrue(command.contains("/tmp/jaipilot-jacoco-init.gradle"));
        assertTrue(command.contains(":clients:test"));
        assertTrue(command.contains("--tests"));
        assertTrue(command.contains("com.example.CrashControllerTest"));
        assertTrue(command.contains(":clients:jacocoTestReport"));
    }

    @Test
    void resolvesWrapperFromAncestorProjectRoot() throws Exception {
        Path root = tempDir.resolve("repo");
        Path moduleDir = root.resolve("module-a");
        Files.createDirectories(moduleDir);
        Path wrapper = root.resolve("gradlew");
        Files.writeString(wrapper, "#!/usr/bin/env sh\n");

        assertEquals(wrapper.toString(), commandBuilder.resolveGradleExecutable(moduleDir, null));
    }

    @Test
    void fallsBackToSystemGradleWhenWrapperMissing() throws Exception {
        Path root = tempDir.resolve("repo");
        Path moduleDir = root.resolve("module-a");
        Files.createDirectories(moduleDir);

        assertTrue(commandBuilder.resolveGradleExecutable(moduleDir, null).contains("gradle"));
    }
}
