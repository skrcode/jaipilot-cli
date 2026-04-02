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
    void buildsVerificationCommand() {
        List<String> command = commandBuilder.buildVerification(
                Path.of("/tmp/build-root"),
                Path.of("custom-gradle"),
                List.of("--stacktrace"),
                false
        );

        assertEquals("custom-gradle", command.get(0));
        assertTrue(command.contains("--no-daemon"));
        assertTrue(command.contains("--console=plain"));
        assertTrue(command.contains("--stacktrace"));
        assertTrue(command.contains("clean"));
        assertTrue(command.contains("test"));
        assertTrue(command.contains("jacocoTestReport"));
    }

    @Test
    void buildsTestCompileCommand() {
        List<String> command = commandBuilder.buildTestCompile(
                Path.of("/tmp/project"),
                Path.of("custom-gradle"),
                List.of("--stacktrace")
        );

        assertEquals("custom-gradle", command.get(0));
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
    void buildsSingleTestCoverageCommand() {
        List<String> command = commandBuilder.buildSingleTestCoverage(
                Path.of("/tmp/project"),
                Path.of("custom-gradle"),
                List.of("--stacktrace"),
                "com.example.CrashControllerTest",
                Path.of("/tmp/jacoco-init.gradle")
        );

        assertEquals("custom-gradle", command.get(0));
        assertTrue(command.contains("--stacktrace"));
        assertTrue(command.contains("-I"));
        assertTrue(command.contains("/tmp/jacoco-init.gradle"));
        assertTrue(command.contains("test"));
        assertTrue(command.contains("--tests"));
        assertTrue(command.contains("jacocoTestReport"));
    }

    @Test
    void buildsModuleScopedSingleTestCoverageCommand() {
        List<String> command = commandBuilder.buildSingleTestCoverage(
                Path.of("/tmp/project"),
                Path.of("custom-gradle"),
                List.of("--stacktrace"),
                "com.example.CrashControllerTest",
                Path.of("/tmp/jacoco-init.gradle"),
                ":clients"
        );

        assertTrue(command.contains(":clients:test"));
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
}
