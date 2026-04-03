package com.jaipilot.cli.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jaipilot.cli.backend.JunitLlmBackendClient;
import com.jaipilot.cli.files.ProjectFileService;
import com.jaipilot.cli.model.FetchJobResponse;
import com.jaipilot.cli.model.InvokeJunitLlmRequest;
import com.jaipilot.cli.model.InvokeJunitLlmResponse;
import com.jaipilot.cli.model.JunitLlmOperation;
import com.jaipilot.cli.model.JunitLlmSessionRequest;
import com.jaipilot.cli.model.JunitLlmSessionResult;
import com.jaipilot.cli.process.GradleCommandBuilder;
import com.jaipilot.cli.process.MavenCommandBuilder;
import com.jaipilot.cli.process.ProcessExecutor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JunitLlmWorkflowRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void runSucceedsWhenGeneratedTestPassesValidation() throws Exception {
        Path projectRoot = tempDir.resolve("project");
        write(projectRoot.resolve("pom.xml"), "<project/>");
        Path cutPath = write(
                projectRoot.resolve("src/main/java/com/example/CrashController.java"),
                """
                package com.example;

                public class CrashController {
                }
                """
        );
        Path outputPath = projectRoot.resolve("src/test/java/com/example/CrashControllerTest.java");
        Path fakeMaven = writeFakeMaven(projectRoot);

        StubBackendClient backendClient = new StubBackendClient(
                """
                package com.example;

                class CrashControllerTest {
                    // PASS
                }
                """
        );
        ProjectFileService fileService = new ProjectFileService();
        JunitLlmSessionRunner sessionRunner = new JunitLlmSessionRunner(
                backendClient,
                fileService,
                new UsedContextClassPathCache(tempDir.resolve("used-context-cache.json")),
                new JunitLlmConsoleLogger(new PrintWriter(new StringWriter()))
        );
        JunitLlmWorkflowRunner workflowRunner = new JunitLlmWorkflowRunner(
                sessionRunner,
                new MavenCommandBuilder(),
                new GradleCommandBuilder(),
                new ProcessExecutor(),
                fileService
        );

        JunitLlmSessionResult result = workflowRunner.run(
                new JunitLlmSessionRequest(
                        projectRoot,
                        cutPath,
                        outputPath,
                        JunitLlmOperation.GENERATE,
                        null,
                        "",
                        "",
                        null
                ),
                fakeMaven,
                List.of(),
                Duration.ofSeconds(10)
        );

        assertEquals("session-1", result.sessionId());
        assertTrue(Files.readString(outputPath).contains("PASS"));
        List<String> commandLog = Files.readAllLines(projectRoot.resolve("maven-commands.log"));
        assertEquals(7, commandLog.size());
        assertTrue(commandLog.stream().filter(line -> line.contains("test-compile")).count() >= 2);
        assertTrue(commandLog.stream().filter(line -> line.contains("verify")).count() >= 2);
        assertTrue(commandLog.stream().anyMatch(line -> line.contains("dependency:sources")));
        assertTrue(commandLog.stream().anyMatch(line -> line.contains("-Dtest=com.example.CrashControllerTest") && line.contains(" test")));
        assertTrue(commandLog.stream().anyMatch(line -> line.contains("jacoco-maven-plugin")));
    }

    @Test
    void runFailsWhenGeneratedTestDoesNotCompile() throws Exception {
        Path projectRoot = tempDir.resolve("compile-fail-project");
        write(projectRoot.resolve("pom.xml"), "<project/>");
        Path cutPath = write(
                projectRoot.resolve("src/main/java/com/example/CrashController.java"),
                """
                package com.example;

                public class CrashController {
                }
                """
        );
        Path outputPath = projectRoot.resolve("src/test/java/com/example/CrashControllerTest.java");
        Path fakeMaven = writeFakeMaven(projectRoot);

        StubBackendClient backendClient = new StubBackendClient(
                """
                package com.example;

                class CrashControllerTest {
                    // BUILD_FAIL
                }
                """
        );
        ProjectFileService fileService = new ProjectFileService();
        JunitLlmSessionRunner sessionRunner = new JunitLlmSessionRunner(
                backendClient,
                fileService,
                new UsedContextClassPathCache(tempDir.resolve("used-context-cache.json")),
                new JunitLlmConsoleLogger(new PrintWriter(new StringWriter()))
        );
        JunitLlmWorkflowRunner workflowRunner = new JunitLlmWorkflowRunner(
                sessionRunner,
                new MavenCommandBuilder(),
                new GradleCommandBuilder(),
                new ProcessExecutor(),
                fileService
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> workflowRunner.run(
                        new JunitLlmSessionRequest(
                                projectRoot,
                                cutPath,
                                outputPath,
                                JunitLlmOperation.GENERATE,
                                null,
                                "",
                                "",
                                null
                        ),
                        fakeMaven,
                        List.of(),
                        Duration.ofSeconds(10)
                )
        );

        assertTrue(exception.getMessage().contains("Failed phase: test-compile"));
        assertTrue(backendClient.requests.size() > 1);
        assertEquals("generate", backendClient.requests.get(0).type());
        assertEquals("fix", backendClient.requests.get(1).type());
        List<String> commandLog = Files.readAllLines(projectRoot.resolve("maven-commands.log"));
        assertEquals(4, commandLog.size());
        assertEquals(2, commandLog.stream().filter(line -> line.contains("test-compile")).count());
        assertEquals(1, commandLog.stream().filter(line -> line.contains("verify")).count());
        assertEquals(1, commandLog.stream().filter(line -> line.contains("dependency:sources")).count());
    }

    @Test
    void runFixesGeneratedTestWhenFirstPassFailsValidation() throws Exception {
        Path projectRoot = tempDir.resolve("fix-success-project");
        write(projectRoot.resolve("pom.xml"), "<project/>");
        Path cutPath = write(
                projectRoot.resolve("src/main/java/com/example/CrashController.java"),
                """
                package com.example;

                public class CrashController {
                }
                """
        );
        Path outputPath = projectRoot.resolve("src/test/java/com/example/CrashControllerTest.java");
        Path fakeMaven = writeFakeMaven(projectRoot);

        StubBackendClient backendClient = new StubBackendClient(
                """
                package com.example;

                class CrashControllerTest {
                    // BUILD_FAIL
                }
                """,
                """
                package com.example;

                class CrashControllerTest {
                    // PASS
                }
                """
        );
        ProjectFileService fileService = new ProjectFileService();
        JunitLlmSessionRunner sessionRunner = new JunitLlmSessionRunner(
                backendClient,
                fileService,
                new UsedContextClassPathCache(tempDir.resolve("used-context-cache.json")),
                new JunitLlmConsoleLogger(new PrintWriter(new StringWriter()))
        );
        JunitLlmWorkflowRunner workflowRunner = new JunitLlmWorkflowRunner(
                sessionRunner,
                new MavenCommandBuilder(),
                new GradleCommandBuilder(),
                new ProcessExecutor(),
                fileService
        );

        JunitLlmSessionResult result = workflowRunner.run(
                new JunitLlmSessionRequest(
                        projectRoot,
                        cutPath,
                        outputPath,
                        JunitLlmOperation.GENERATE,
                        null,
                        "",
                        "",
                        null
                ),
                fakeMaven,
                List.of(),
                Duration.ofSeconds(10)
        );

        assertEquals(2, backendClient.requests.size());
        assertEquals("generate", backendClient.requests.get(0).type());
        assertEquals("fix", backendClient.requests.get(1).type());
        assertTrue(backendClient.requests.get(1).clientLogs().contains("Failed phase: test-compile"));
        assertTrue(Files.readString(result.outputPath()).contains("PASS"));
    }

    @Test
    void runFixesGeneratedTestWhenCodebaseRulesFail() throws Exception {
        Path projectRoot = tempDir.resolve("rules-fail-project");
        write(projectRoot.resolve("pom.xml"), "<project/>");
        Path cutPath = write(
                projectRoot.resolve("src/main/java/com/example/CrashController.java"),
                """
                package com.example;

                public class CrashController {
                }
                """
        );
        Path outputPath = projectRoot.resolve("src/test/java/com/example/CrashControllerTest.java");
        Path fakeMaven = writeFakeMaven(projectRoot);

        StubBackendClient backendClient = new StubBackendClient(
                """
                package com.example;

                class CrashControllerTest {
                    // RULE_FAIL
                }
                """,
                """
                package com.example;

                class CrashControllerTest {
                    // PASS
                }
                """
        );
        ProjectFileService fileService = new ProjectFileService();
        JunitLlmSessionRunner sessionRunner = new JunitLlmSessionRunner(
                backendClient,
                fileService,
                new UsedContextClassPathCache(tempDir.resolve("used-context-cache.json")),
                new JunitLlmConsoleLogger(new PrintWriter(new StringWriter()))
        );
        JunitLlmWorkflowRunner workflowRunner = new JunitLlmWorkflowRunner(
                sessionRunner,
                new MavenCommandBuilder(),
                new GradleCommandBuilder(),
                new ProcessExecutor(),
                fileService
        );

        JunitLlmSessionResult result = workflowRunner.run(
                new JunitLlmSessionRequest(
                        projectRoot,
                        cutPath,
                        outputPath,
                        JunitLlmOperation.GENERATE,
                        null,
                        "",
                        "",
                        null
                ),
                fakeMaven,
                List.of(),
                Duration.ofSeconds(10)
        );

        assertEquals("session-1", result.sessionId());
        assertEquals(2, backendClient.requests.size());
        assertEquals("generate", backendClient.requests.get(0).type());
        assertEquals("fix", backendClient.requests.get(1).type());
        assertTrue(backendClient.requests.get(1).clientLogs().contains("Failed phase: codebase-rules"));
        assertTrue(Files.readString(result.outputPath()).contains("PASS"));
        List<String> commandLog = Files.readAllLines(projectRoot.resolve("maven-commands.log"));
        assertTrue(commandLog.stream().filter(line -> line.contains("verify")).count() >= 3);
    }

    @Test
    void runFailsPreflightWhenUnrelatedCodebaseRulesAlreadyFail() throws Exception {
        Path projectRoot = tempDir.resolve("preflight-rules-fail-project");
        write(projectRoot.resolve("pom.xml"), "<project/>");
        Path cutPath = write(
                projectRoot.resolve("src/main/java/com/example/CrashController.java"),
                """
                package com.example;

                public class CrashController {
                }
                """
        );
        write(
                projectRoot.resolve("src/main/java/com/example/BrokenDependency.java"),
                """
                package com.example;

                public class BrokenDependency {
                    // UNRELATED_RULE_FAIL
                }
                """
        );
        Path outputPath = projectRoot.resolve("src/test/java/com/example/CrashControllerTest.java");
        Path fakeMaven = writeFakeMaven(projectRoot);

        StubBackendClient backendClient = new StubBackendClient(
                """
                package com.example;

                class CrashControllerTest {
                    // PASS
                }
                """
        );
        ProjectFileService fileService = new ProjectFileService();
        JunitLlmSessionRunner sessionRunner = new JunitLlmSessionRunner(
                backendClient,
                fileService,
                new UsedContextClassPathCache(tempDir.resolve("used-context-cache.json")),
                new JunitLlmConsoleLogger(new PrintWriter(new StringWriter()))
        );
        JunitLlmWorkflowRunner workflowRunner = new JunitLlmWorkflowRunner(
                sessionRunner,
                new MavenCommandBuilder(),
                new GradleCommandBuilder(),
                new ProcessExecutor(),
                fileService
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> workflowRunner.run(
                        new JunitLlmSessionRequest(
                                projectRoot,
                                cutPath,
                                outputPath,
                                JunitLlmOperation.GENERATE,
                                null,
                                "",
                                "",
                                null
                        ),
                        fakeMaven,
                        List.of(),
                        Duration.ofSeconds(10)
                )
        );

        assertTrue(exception.getMessage().contains("Failed phase: preflight-codebase-rules"));
        assertEquals(0, backendClient.requests.size());
        List<String> commandLog = Files.readAllLines(projectRoot.resolve("maven-commands.log"));
        assertEquals(2, commandLog.size());
        assertTrue(commandLog.stream().anyMatch(line -> line.contains("test-compile")));
        assertTrue(commandLog.stream().anyMatch(line -> line.contains("verify")));
    }

    @Test
    void runKeepsActionableTestFailureDetailsInFixLogs() throws Exception {
        Path projectRoot = tempDir.resolve("fix-test-failure-details-project");
        write(projectRoot.resolve("pom.xml"), "<project/>");
        Path cutPath = write(
                projectRoot.resolve("src/main/java/com/example/CrashController.java"),
                """
                package com.example;

                public class CrashController {
                }
                """
        );
        Path outputPath = projectRoot.resolve("src/test/java/com/example/CrashControllerTest.java");
        Path fakeMaven = writeFakeMaven(projectRoot);

        StubBackendClient backendClient = new StubBackendClient(
                """
                package com.example;

                class CrashControllerTest {
                    // TEST_FAIL
                }
                """,
                """
                package com.example;

                class CrashControllerTest {
                    // PASS
                }
                """
        );
        ProjectFileService fileService = new ProjectFileService();
        JunitLlmSessionRunner sessionRunner = new JunitLlmSessionRunner(
                backendClient,
                fileService,
                new UsedContextClassPathCache(tempDir.resolve("used-context-cache.json")),
                new JunitLlmConsoleLogger(new PrintWriter(new StringWriter()))
        );
        JunitLlmWorkflowRunner workflowRunner = new JunitLlmWorkflowRunner(
                sessionRunner,
                new MavenCommandBuilder(),
                new GradleCommandBuilder(),
                new ProcessExecutor(),
                fileService
        );

        JunitLlmSessionResult result = workflowRunner.run(
                new JunitLlmSessionRequest(
                        projectRoot,
                        cutPath,
                        outputPath,
                        JunitLlmOperation.GENERATE,
                        null,
                        "",
                        "",
                        null
                ),
                fakeMaven,
                List.of(),
                Duration.ofSeconds(10)
        );

        assertEquals("session-1", result.sessionId());
        assertEquals(2, backendClient.requests.size());
        String clientLogs = backendClient.requests.get(1).clientLogs();
        assertTrue(clientLogs.contains("Failed phase: test"));
        assertTrue(clientLogs.contains("AssertionFailedError: expected: <200> but was: <500>"));
        assertTrue(clientLogs.contains("Caused by: java.lang.IllegalStateException: boom"));
        assertFalse(clientLogs.contains("CrashControllerTest.shouldReturnOk"));
        assertFalse(clientLogs.contains("AssertionFailureBuilder.build("));
    }

    @Test
    void runStreamsBuildLogsWhenEnabled() throws Exception {
        Path projectRoot = tempDir.resolve("project-with-logs");
        write(projectRoot.resolve("pom.xml"), "<project/>");
        Path cutPath = write(
                projectRoot.resolve("src/main/java/com/example/CrashController.java"),
                """
                package com.example;

                public class CrashController {
                }
                """
        );
        Path outputPath = projectRoot.resolve("src/test/java/com/example/CrashControllerTest.java");
        Path fakeMaven = writeFakeMaven(projectRoot);

        StubBackendClient backendClient = new StubBackendClient(
                """
                package com.example;

                class CrashControllerTest {
                    // PASS
                }
                """
        );
        ProjectFileService fileService = new ProjectFileService();
        JunitLlmSessionRunner sessionRunner = new JunitLlmSessionRunner(
                backendClient,
                fileService,
                new UsedContextClassPathCache(tempDir.resolve("used-context-cache.json")),
                new JunitLlmConsoleLogger(new PrintWriter(new StringWriter()))
        );
        StringWriter buildLogBuffer = new StringWriter();
        JunitLlmWorkflowRunner workflowRunner = new JunitLlmWorkflowRunner(
                sessionRunner,
                new MavenCommandBuilder(),
                new GradleCommandBuilder(),
                new ProcessExecutor(),
                fileService,
                true,
                new PrintWriter(buildLogBuffer, true)
        );

        workflowRunner.run(
                new JunitLlmSessionRequest(
                        projectRoot,
                        cutPath,
                        outputPath,
                        JunitLlmOperation.GENERATE,
                        null,
                        "",
                        "",
                        null
                ),
                fakeMaven,
                List.of(),
                Duration.ofSeconds(10)
        );

        String buildLogs = buildLogBuffer.toString();
        assertTrue(buildLogs.contains("compile ok"));
        assertTrue(buildLogs.contains("Tests run: 1, Failures: 0"));
        assertTrue(buildLogs.contains("PROGRESS: Maximizing coverage..."));
        assertTrue(buildLogs.contains("PROGRESS: Coverage before maximizing:"));
        assertTrue(buildLogs.contains("PROGRESS: Coverage after maximizing:"));
    }

    @Test
    void runImprovesCoverageToReachTarget() throws Exception {
        Path projectRoot = tempDir.resolve("coverage-improvement-project");
        write(projectRoot.resolve("pom.xml"), "<project/>");
        Path cutPath = write(
                projectRoot.resolve("src/main/java/com/example/CrashController.java"),
                """
                package com.example;

                public class CrashController {
                }
                """
        );
        Path outputPath = projectRoot.resolve("src/test/java/com/example/CrashControllerTest.java");
        Path fakeMaven = writeFakeMaven(projectRoot);

        StubBackendClient backendClient = new StubBackendClient(
                """
                package com.example;

                class CrashControllerTest {
                    // PASS
                    // LOW_COVERAGE
                }
                """,
                """
                package com.example;

                class CrashControllerTest {
                    // PASS
                    // HIGH_COVERAGE
                }
                """
        );
        ProjectFileService fileService = new ProjectFileService();
        JunitLlmSessionRunner sessionRunner = new JunitLlmSessionRunner(
                backendClient,
                fileService,
                new UsedContextClassPathCache(tempDir.resolve("used-context-cache.json")),
                new JunitLlmConsoleLogger(new PrintWriter(new StringWriter()))
        );
        StringWriter buildLogBuffer = new StringWriter();
        JunitLlmWorkflowRunner workflowRunner = new JunitLlmWorkflowRunner(
                sessionRunner,
                new MavenCommandBuilder(),
                new GradleCommandBuilder(),
                new ProcessExecutor(),
                fileService,
                true,
                new PrintWriter(buildLogBuffer, true)
        );

        JunitLlmSessionResult result = workflowRunner.run(
                new JunitLlmSessionRequest(
                        projectRoot,
                        cutPath,
                        outputPath,
                        JunitLlmOperation.GENERATE,
                        null,
                        "",
                        "",
                        null
                ),
                fakeMaven,
                List.of(),
                Duration.ofSeconds(10)
        );

        assertEquals("session-1", result.sessionId());
        assertEquals(2, backendClient.requests.size());
        assertEquals("generate", backendClient.requests.get(0).type());
        assertEquals("fix", backendClient.requests.get(1).type());
        assertTrue(backendClient.requests.get(1).clientLogs().contains("Coverage target: 80.00% line coverage."));
        String buildLogs = buildLogBuffer.toString();
        assertTrue(buildLogs.contains("Coverage before maximizing: 30.00%"));
        assertTrue(buildLogs.contains("Coverage after maximizing: 90.00%"));
    }

    @Test
    void runRetriesAfterAutoDownloadingDependencySourcesForMissingContextPath() throws Exception {
        Path projectRoot = tempDir.resolve("project-with-missing-context");
        write(projectRoot.resolve("pom.xml"), "<project/>");
        Path cutPath = write(
                projectRoot.resolve("src/main/java/com/example/CrashController.java"),
                """
                package com.example;

                public class CrashController {
                }
                """
        );
        Path outputPath = projectRoot.resolve("src/test/java/com/example/CrashControllerTest.java");
        Path fakeMaven = writeFakeMavenThatDownloadsDependencySources(projectRoot);

        JunitLlmBackendClient backendClient = new JunitLlmBackendClient() {
            private int invokeCount;

            @Override
            public InvokeJunitLlmResponse invoke(InvokeJunitLlmRequest request) {
                invokeCount++;
                return new InvokeJunitLlmResponse("job-" + invokeCount, "session-1");
            }

            @Override
            public FetchJobResponse fetchJob(String jobId) {
                if ("job-1".equals(jobId)) {
                    return new FetchJobResponse(
                            "done",
                            new FetchJobResponse.FetchJobOutput(
                                    "session-1",
                                    """
                                    package com.example;

                                    class CrashControllerTest {
                                    }
                                    """,
                                    List.of("org/acme/jaipilot/workflowretry/DownloadedContextForWorkflowRetry.java"),
                                    List.of()
                            ),
                            null,
                            null
                    );
                }
                return new FetchJobResponse(
                        "done",
                        new FetchJobResponse.FetchJobOutput(
                                "session-1",
                                """
                                package com.example;

                                class CrashControllerTest {
                                }
                                """,
                                List.of(),
                                List.of()
                        ),
                        null,
                        null
                );
            }
        };

        ProjectFileService fileService = new ProjectFileService();
        JunitLlmSessionRunner sessionRunner = new JunitLlmSessionRunner(
                backendClient,
                fileService,
                new UsedContextClassPathCache(tempDir.resolve("used-context-cache.json")),
                new JunitLlmConsoleLogger(new PrintWriter(new StringWriter()))
        );
        JunitLlmWorkflowRunner workflowRunner = new JunitLlmWorkflowRunner(
                sessionRunner,
                new MavenCommandBuilder(),
                new GradleCommandBuilder(),
                new ProcessExecutor(),
                fileService
        );

        JunitLlmSessionResult result = workflowRunner.run(
                new JunitLlmSessionRequest(
                        projectRoot,
                        cutPath,
                        outputPath,
                        JunitLlmOperation.GENERATE,
                        null,
                        "",
                        "",
                        null
                ),
                fakeMaven,
                List.of(),
                Duration.ofSeconds(10)
        );

        assertEquals("session-1", result.sessionId());
        List<String> commandLog = Files.readAllLines(projectRoot.resolve("maven-commands.log"));
        assertTrue(commandLog.stream().anyMatch(line -> line.contains("dependency:sources")));
        assertTrue(Files.isRegularFile(projectRoot.resolve(
                "src/main/java/org/acme/jaipilot/workflowretry/DownloadedContextForWorkflowRetry.java"
        )));
    }

    private Path write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }

    private Path writeFakeMaven(Path projectRoot) throws Exception {
        Path fakeMaven = projectRoot.resolve("mvnw");
        Files.createDirectories(projectRoot);
        write(
                projectRoot.resolve(".mvn/wrapper/maven-wrapper.properties"),
                "distributionUrl=https://repo.maven.apache.org/maven2\n"
        );
        Files.writeString(fakeMaven, """
                #!/usr/bin/env sh
                set -eu

                TEST_FILE="$PWD/src/test/java/com/example/CrashControllerTest.java"
                printf '%s\\n' "$*" >> "$PWD/maven-commands.log"

                case "$*" in
                  *dependency:sources*)
                    echo "downloaded sources"
                    exit 0
                    ;;
                  *test-compile*)
                    if grep -q UNRELATED_BUILD_FAIL "$PWD/src/main/java/com/example/BrokenDependency.java" 2>/dev/null; then
                      echo "compile failed: unrelated dependency"
                      exit 1
                    fi
                    if grep -q BUILD_FAIL "$TEST_FILE" 2>/dev/null; then
                      echo "compile failed: missing symbol"
                      exit 1
                    fi
                    echo "compile ok"
                    exit 0
                    ;;
                  *verify*)
                    if grep -q UNRELATED_RULE_FAIL "$PWD/src/main/java/com/example/BrokenDependency.java" 2>/dev/null; then
                      echo "rule check failed: unrelated codebase rule"
                      exit 1
                    fi
                    if grep -q RULE_FAIL "$TEST_FILE" 2>/dev/null; then
                      echo "rule check failed: unused imports"
                      exit 1
                    fi
                    echo "codebase rules ok"
                    exit 0
                    ;;
                  *jacoco-maven-plugin*)
                    mkdir -p "$PWD/target/site/jacoco"
                    COVERED=16
                    MISSED=4
                    if grep -q LOW_COVERAGE "$TEST_FILE" 2>/dev/null; then
                      COVERED=6
                      MISSED=14
                    fi
                    if grep -q HIGH_COVERAGE "$TEST_FILE" 2>/dev/null; then
                      COVERED=18
                      MISSED=2
                    fi
                    cat > "$PWD/target/site/jacoco/jacoco.xml" <<EOF
                <?xml version="1.0" encoding="UTF-8"?>
                <report name="jaipilot">
                  <package name="com/example">
                    <sourcefile name="CrashController.java">
                      <counter type="LINE" missed="$MISSED" covered="$COVERED"/>
                    </sourcefile>
                  </package>
                </report>
                EOF
                    echo "coverage report generated"
                    exit 0
                    ;;
                  *-Dtest=com.example.CrashControllerTest*test)
                    if grep -q TEST_FAIL "$TEST_FILE" 2>/dev/null; then
                      echo "Tests run: 1, Failures: 1"
                      echo "org.opentest4j.AssertionFailedError: expected: <200> but was: <500>"
                      echo "    at com.example.CrashControllerTest.shouldReturnOk(CrashControllerTest.java:42)"
                      echo "    at org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)"
                      echo "Caused by: java.lang.IllegalStateException: boom"
                      exit 1
                    fi
                    echo "Tests run: 1, Failures: 0"
                    exit 0
                    ;;
                  *)
                    echo "unexpected command: $*"
                    exit 2
                    ;;
                esac
                """);
        boolean executable = fakeMaven.toFile().setExecutable(true);
        assertTrue(executable, "fake Maven script must be executable");
        return fakeMaven;
    }

    private Path writeFakeMavenThatDownloadsDependencySources(Path projectRoot) throws Exception {
        Path fakeMaven = projectRoot.resolve("mvnw");
        Files.createDirectories(projectRoot);
        write(
                projectRoot.resolve(".mvn/wrapper/maven-wrapper.properties"),
                "distributionUrl=https://repo.maven.apache.org/maven2\n"
        );
        Files.writeString(fakeMaven, """
                #!/usr/bin/env sh
                set -eu

                printf '%s\\n' "$*" >> "$PWD/maven-commands.log"

                case "$*" in
                  *dependency:sources*)
                    mkdir -p "$PWD/src/main/java/org/acme/jaipilot/workflowretry"
                    cat > "$PWD/src/main/java/org/acme/jaipilot/workflowretry/DownloadedContextForWorkflowRetry.java" <<'EOF'
                package org.acme.jaipilot.workflowretry;

                public class DownloadedContextForWorkflowRetry {
                }
                EOF
                    echo "downloaded sources"
                    exit 0
                    ;;
                  *test-compile*)
                    echo "compile ok"
                    exit 0
                    ;;
                  *verify*)
                    echo "codebase rules ok"
                    exit 0
                    ;;
                  *jacoco-maven-plugin*)
                    mkdir -p "$PWD/target/site/jacoco"
                    cat > "$PWD/target/site/jacoco/jacoco.xml" <<EOF
                <?xml version="1.0" encoding="UTF-8"?>
                <report name="jaipilot">
                  <package name="com/example">
                    <sourcefile name="CrashController.java">
                      <counter type="LINE" missed="4" covered="16"/>
                    </sourcefile>
                  </package>
                </report>
                EOF
                    echo "coverage report generated"
                    exit 0
                    ;;
                  *-Dtest=com.example.CrashControllerTest*test)
                    echo "Tests run: 1, Failures: 0"
                    exit 0
                    ;;
                  *)
                    echo "unexpected command: $*"
                    exit 2
                    ;;
                esac
                """);
        boolean executable = fakeMaven.toFile().setExecutable(true);
        assertTrue(executable, "fake Maven script must be executable");
        return fakeMaven;
    }

    private static final class StubBackendClient implements JunitLlmBackendClient {

        private final List<InvokeJunitLlmRequest> requests = new ArrayList<>();
        private final List<String> finalTestFiles;

        private StubBackendClient(String... finalTestFiles) {
            this.finalTestFiles = List.of(finalTestFiles);
        }

        @Override
        public InvokeJunitLlmResponse invoke(InvokeJunitLlmRequest request) {
            requests.add(request);
            return new InvokeJunitLlmResponse("job-" + requests.size(), "session-1");
        }

        @Override
        public FetchJobResponse fetchJob(String jobId) {
            int index = Integer.parseInt(jobId.substring("job-".length())) - 1;
            String finalTestFile = finalTestFiles.isEmpty()
                    ? "package com.example;\nclass CrashControllerTest {}\n"
                    : finalTestFiles.get(Math.max(0, Math.min(index, finalTestFiles.size() - 1)));
            return new FetchJobResponse(
                    "done",
                    new FetchJobResponse.FetchJobOutput(
                            "session-1",
                            finalTestFile,
                            List.of(),
                            List.of()
                    ),
                    null,
                    null
            );
        }
    }
}
