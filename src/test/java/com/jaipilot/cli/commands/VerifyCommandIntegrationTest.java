package com.jaipilot.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jaipilot.cli.JaiPilotCli;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class VerifyCommandIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void printsDetailedFailureReportForZeroConfigJunit5Project() throws Exception {
        Path projectRoot = tempDir.resolve("sample-project");
        writeFailingJunit5Project(projectRoot);

        StringWriter outBuffer = new StringWriter();
        StringWriter errBuffer = new StringWriter();
        CommandLine commandLine = new CommandLine(new JaiPilotCli())
                .setOut(new PrintWriter(outBuffer, true))
                .setErr(new PrintWriter(errBuffer, true));

        int exitCode = commandLine.execute(
                "verify",
                "--project-root", projectRoot.toString(),
                "--maven-executable", repoMavenWrapper().toString(),
                "--line-coverage-threshold", "100",
                "--branch-coverage-threshold", "100",
                "--instruction-coverage-threshold", "100",
                "--max-actionable-items", "3"
        );

        String output = outBuffer.toString();
        assertEquals(1, exitCode, errBuffer.toString());
        assertTrue(output.contains("STATUS: FAIL"));
        assertTrue(output.contains("THRESHOLDS:"));
        assertTrue(output.contains("COVERAGE_FINDING: metric=LINE"));
        assertTrue(output.contains("BUILD_ISSUE: NONE"));
        assertTrue(output.contains("NEXT_STEP: Implement the listed test improvements"));
        assertTrue(output.contains("file=src/main/java/com/example/Calculator.java"));
    }

    @Test
    void printsDetailedFailureReportForGradleProject() throws Exception {
        Path projectRoot = tempDir.resolve("gradle-project");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("build.gradle.kts"), "plugins { java }");
        Files.createDirectories(projectRoot.resolve("report-fixtures"));
        Files.copy(resourcePath("jacoco.xml"), projectRoot.resolve("report-fixtures/jacoco.xml"));
        Path fakeGradle = projectRoot.resolve("gradlew");
        Files.writeString(fakeGradle, """
                #!/usr/bin/env sh
                set -eu

                mkdir -p "$PWD/build/reports/jacoco/test"
                cp "$PWD/report-fixtures/jacoco.xml" "$PWD/build/reports/jacoco/test/jacocoTestReport.xml"
                exit 0
                """);
        fakeGradle.toFile().setExecutable(true);

        StringWriter outBuffer = new StringWriter();
        StringWriter errBuffer = new StringWriter();
        CommandLine commandLine = new CommandLine(new JaiPilotCli())
                .setOut(new PrintWriter(outBuffer, true))
                .setErr(new PrintWriter(errBuffer, true));

        int exitCode = commandLine.execute(
                "verify",
                "--project-root", projectRoot.toString(),
                "--build-executable", fakeGradle.toString(),
                "--line-coverage-threshold", "100",
                "--branch-coverage-threshold", "100",
                "--instruction-coverage-threshold", "100",
                "--max-actionable-items", "3"
        );

        String output = outBuffer.toString();
        assertEquals(1, exitCode, errBuffer.toString());
        assertTrue(output.contains("STATUS: FAIL"));
        assertTrue(output.contains("BUILD_ISSUE: NONE"));
        assertTrue(output.contains("COVERAGE_FINDING: metric=LINE"));
        assertTrue(output.contains("file=src/main/java/com/example/service/OrderService.java"));
    }

    private void writeFailingJunit5Project(Path projectRoot) throws Exception {
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>calculator</artifactId>
                    <version>1.0.0</version>
                    <properties>
                        <maven.compiler.release>17</maven.compiler.release>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>org.junit.jupiter</groupId>
                            <artifactId>junit-jupiter</artifactId>
                            <version>5.11.4</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-surefire-plugin</artifactId>
                                <version>3.5.2</version>
                                <configuration>
                                    <useModulePath>false</useModulePath>
                                </configuration>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """);
        writeJava(projectRoot.resolve("src/main/java/com/example/Calculator.java"), """
                package com.example;

                public class Calculator {
                    public int adjust(int value) {
                        if (value > 10) {
                            return value - 1;
                        }
                        return value + 1;
                    }

                    public int square(int value) {
                        return value * value;
                    }
                }
                """);
        writeJava(projectRoot.resolve("src/test/java/com/example/CalculatorTest.java"), """
                package com.example;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                class CalculatorTest {
                    @Test
                    void adjustsLargeValues() {
                        assertEquals(11, new Calculator().adjust(12));
                    }
                }
                """);
    }

    private void writeJava(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private Path repoMavenWrapper() {
        return Path.of(System.getProperty("user.dir")).resolve("mvnw").toAbsolutePath().normalize();
    }

    private static Path resourcePath(String fileName) {
        return Path.of("src", "test", "resources", "reports", fileName);
    }
}
