package com.jaipilot.cli.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class MavenCommandBuilderTest {

    private final MavenCommandBuilder commandBuilder = new MavenCommandBuilder();

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
}
