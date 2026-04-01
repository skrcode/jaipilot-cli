package com.jaipilot.cli.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenCommandBuilderTest {

    private final MavenCommandBuilder commandBuilder = new MavenCommandBuilder();

    @TempDir
    Path tempDir;

    @Test
    void buildsExpectedGoalsPropertiesAndPomSelection() {
        List<String> command = commandBuilder.build(
                Path.of("/tmp/build-root"),
                Path.of("/tmp/build-root/pom.xml"),
                Path.of("custom-mvn"),
                List.of("-DskipITs"),
                "0.8.13",
                "1.22.0",
                false,
                true
        );

        assertEquals("custom-mvn", command.get(0));
        assertTrue(command.contains("-f"));
        assertTrue(command.contains("/tmp/build-root/pom.xml"));
        assertTrue(command.contains("-DoutputFormats=XML"));
        assertTrue(command.contains("-DtimestampedReports=false"));
        assertTrue(command.contains("-DreportsDirectory=target/pit-reports"));
        assertTrue(command.contains("-Dthreads=" + commandBuilder.defaultPitThreads()));
        assertTrue(command.contains("clean"));
        assertTrue(command.contains("org.jacoco:jacoco-maven-plugin:0.8.13:prepare-agent"));
        assertTrue(command.contains("org.jacoco:jacoco-maven-plugin:0.8.13:report"));
        assertTrue(command.contains("org.jacoco:jacoco-maven-plugin:0.8.13:report-aggregate"));
        assertTrue(command.contains("org.pitest:pitest-maven:1.22.0:mutationCoverage"));
    }

    @Test
    void buildsTestCompileCommand() {
        List<String> command = commandBuilder.buildTestCompile(
                Path.of("/tmp/project"),
                Path.of("custom-mvn"),
                List.of("-DskipITs")
        );

        assertEquals("custom-mvn", command.get(0));
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
    void buildsSingleTestCoverageCommand() {
        List<String> command = commandBuilder.buildSingleTestCoverage(
                Path.of("/tmp/project"),
                Path.of("custom-mvn"),
                List.of("-DskipITs"),
                "com.example.CrashControllerTest",
                "0.8.13"
        );

        assertEquals("custom-mvn", command.get(0));
        assertTrue(command.contains("-DskipITs"));
        assertTrue(command.contains("-Dtest=com.example.CrashControllerTest"));
        assertTrue(command.contains("org.jacoco:jacoco-maven-plugin:0.8.13:prepare-agent"));
        assertTrue(command.contains("org.jacoco:jacoco-maven-plugin:0.8.13:report"));
    }

    @Test
    void resolvesWrapperFromAncestorProjectRoot() throws Exception {
        Path root = tempDir.resolve("repo");
        Path moduleDir = root.resolve("module-a");
        Files.createDirectories(moduleDir);
        Path wrapper = root.resolve("mvnw");
        Files.writeString(wrapper, "#!/usr/bin/env sh\n");

        assertEquals(wrapper.toString(), commandBuilder.resolveMavenExecutable(moduleDir, null));
    }
}
