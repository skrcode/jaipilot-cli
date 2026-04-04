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
                "package com.example;\npublic class CrashController {}\n"
        );
        Path outputPath = projectRoot.resolve("src/test/java/com/example/CrashControllerTest.java");
        Path fakeMaven = writeFakeMaven(projectRoot);

        StubBackendClient backendClient = new StubBackendClient(outputFinal("""
                package com.example;
                class CrashControllerTest {
                    // PASS
                    // HIGH_COVERAGE
                }
                """));
        JunitLlmWorkflowRunner workflowRunner = newWorkflowRunner(backendClient, new ProjectFileService());

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
        assertEquals(1, backendClient.requests.size());

        List<String> commandLog = Files.readAllLines(projectRoot.resolve("maven-commands.log"));
        assertTrue(commandLog.stream().anyMatch(line -> line.contains("test-compile")));
        assertTrue(commandLog.stream().anyMatch(line -> line.contains("verify")));
        assertTrue(commandLog.stream().anyMatch(line -> line.contains("-Dtest=com.example.CrashControllerTest") && line.contains(" test")));
    }

    @Test
    void runFixesGeneratedTestWhenTargetedTestFails() throws Exception {
        Path projectRoot = tempDir.resolve("targeted-test-fix-project");
        write(projectRoot.resolve("pom.xml"), "<project/>");
        Path cutPath = write(
                projectRoot.resolve("src/main/java/com/example/CrashController.java"),
                "package com.example;\npublic class CrashController {}\n"
        );
        Path outputPath = projectRoot.resolve("src/test/java/com/example/CrashControllerTest.java");
        Path fakeMaven = writeFakeMaven(projectRoot);

        StubBackendClient backendClient = new StubBackendClient(
                outputFinal("""
                        package com.example;
                        class CrashControllerTest {
                            // PASS
                            // TEST_FAIL
                            // HIGH_COVERAGE
                        }
                        """),
                outputFinal("""
                        package com.example;
                        class CrashControllerTest {
                            // PASS
                            // HIGH_COVERAGE
                        }
                        """)
        );
        JunitLlmWorkflowRunner workflowRunner = newWorkflowRunner(backendClient, new ProjectFileService());

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
        assertTrue(backendClient.requests.get(1).clientLogs().contains("Failed phase: test"));
        assertTrue(backendClient.requests.get(1).clientLogs().contains("AssertionFailedError: expected: <200> but was: <500>"));
        assertTrue(Files.readString(outputPath).contains("PASS"));

        List<String> commandLog = Files.readAllLines(projectRoot.resolve("maven-commands.log"));
        assertTrue(commandLog.stream().filter(line -> line.contains("-Dtest=com.example.CrashControllerTest") && line.contains(" test")).count() >= 2);
    }

    @Test
    void runStartsWithValidationAndFixesExistingTestFileWhenItFails() throws Exception {
        Path projectRoot = tempDir.resolve("existing-test-fix-project");
        write(projectRoot.resolve("pom.xml"), "<project/>");
        Path cutPath = write(
                projectRoot.resolve("src/main/java/com/example/CrashController.java"),
                "package com.example;\npublic class CrashController {}\n"
        );
        Path outputPath = write(
                projectRoot.resolve("src/test/java/com/example/CrashControllerTest.java"),
                """
                package com.example;
                class CrashControllerTest {
                    // PASS
                    // TEST_FAIL
                }
                """
        );
        Path fakeMaven = writeFakeMaven(projectRoot);

        StubBackendClient backendClient = new StubBackendClient(
                outputFinal("""
                        package com.example;
                        class CrashControllerTest {
                            // PASS
                        }
                        """)
        );
        JunitLlmWorkflowRunner workflowRunner = newWorkflowRunner(backendClient, new ProjectFileService());

        JunitLlmSessionResult result = workflowRunner.run(
                new JunitLlmSessionRequest(
                        projectRoot,
                        cutPath,
                        outputPath,
                        JunitLlmOperation.GENERATE,
                        null,
                        Files.readString(outputPath),
                        "",
                        null
                ),
                fakeMaven,
                List.of(),
                Duration.ofSeconds(10)
        );

        assertEquals("session-1", result.sessionId());
        assertEquals(1, backendClient.requests.size());
        assertEquals("fix", backendClient.requests.get(0).type());
        assertTrue(backendClient.requests.get(0).clientLogs().contains("Failed phase: test"));
        assertTrue(backendClient.requests.get(0).clientLogs().contains("AssertionFailedError: expected: <200> but was: <500>"));
        assertTrue(Files.readString(outputPath).contains("// PASS"));
        assertFalse(Files.readString(outputPath).contains("TEST_FAIL"));
    }

    @Test
    void runDoesNotTriggerCoverageFixWhenValidationPasses() throws Exception {
        Path projectRoot = tempDir.resolve("coverage-improvement-project");
        write(projectRoot.resolve("pom.xml"), "<project/>");
        Path cutPath = write(
                projectRoot.resolve("src/main/java/com/example/CrashController.java"),
                "package com.example;\npublic class CrashController {}\n"
        );
        Path outputPath = projectRoot.resolve("src/test/java/com/example/CrashControllerTest.java");
        Path fakeMaven = writeFakeMaven(projectRoot);

        StubBackendClient backendClient = new StubBackendClient(
                outputFinal("""
                        package com.example;
                        class CrashControllerTest {
                            // PASS
                            // LOW_COVERAGE
                        }
                        """),
                outputFinal("""
                        package com.example;
                        class CrashControllerTest {
                            // PASS
                            // HIGH_COVERAGE
                        }
                        """)
        );
        JunitLlmWorkflowRunner workflowRunner = newWorkflowRunner(backendClient, new ProjectFileService());

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
        assertEquals(1, backendClient.requests.size());

        List<String> commandLog = Files.readAllLines(projectRoot.resolve("maven-commands.log"));
        assertFalse(commandLog.stream().anyMatch(line -> line.contains("jacoco-maven-plugin:0.8.13:report")));
    }

    @Test
    void runFailsBeforeGenerationWhenUnrelatedCompilationFails() throws Exception {
        Path projectRoot = tempDir.resolve("preflight-fail-project");
        write(projectRoot.resolve("pom.xml"), "<project/>");
        Path cutPath = write(
                projectRoot.resolve("src/main/java/com/example/CrashController.java"),
                "package com.example;\npublic class CrashController {}\n"
        );
        write(
                projectRoot.resolve("src/main/java/com/example/BrokenDependency.java"),
                "package com.example;\nclass BrokenDependency { // UNRELATED_BUILD_FAIL\n}\n"
        );
        Path outputPath = projectRoot.resolve("src/test/java/com/example/CrashControllerTest.java");
        Path fakeMaven = writeFakeMaven(projectRoot);

        StubBackendClient backendClient = new StubBackendClient(outputFinal("package com.example; class CrashControllerTest {}\n"));
        JunitLlmWorkflowRunner workflowRunner = newWorkflowRunner(backendClient, new ProjectFileService());

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

        assertTrue(exception.getMessage().contains("Failed phase: preflight-test-compile"));
        assertEquals(0, backendClient.requests.size());
    }

    @Test
    void runStillValidatesWhenTestIsUnchanged() throws Exception {
        Path projectRoot = tempDir.resolve("unchanged-test-project");
        write(projectRoot.resolve("pom.xml"), "<project/>");
        Path cutPath = write(
                projectRoot.resolve("src/main/java/com/example/CrashController.java"),
                "package com.example;\npublic class CrashController {}\n"
        );
        Path outputPath = write(
                projectRoot.resolve("src/test/java/com/example/CrashControllerTest.java"),
                "package com.example;\nclass CrashControllerTest { // PASS // HIGH_COVERAGE }\n"
        );
        Path fakeMaven = writeFakeMaven(projectRoot);

        String unchangedTest = Files.readString(outputPath);
        StubBackendClient backendClient = new StubBackendClient(outputFinal(unchangedTest));
        JunitLlmWorkflowRunner workflowRunner = newWorkflowRunner(backendClient, new ProjectFileService());

        workflowRunner.run(
                new JunitLlmSessionRequest(
                        projectRoot,
                        cutPath,
                        outputPath,
                        JunitLlmOperation.GENERATE,
                        null,
                        unchangedTest,
                        "",
                        null
                ),
                fakeMaven,
                List.of(),
                Duration.ofSeconds(10)
        );

        List<String> commandLog = Files.readAllLines(projectRoot.resolve("maven-commands.log"));
        assertEquals(5, commandLog.size());
        assertTrue(commandLog.stream().anyMatch(line -> line.contains("test-compile")));
        assertTrue(commandLog.stream().anyMatch(line -> line.contains("verify")));
        assertTrue(commandLog.stream().anyMatch(line -> line.contains("-Dtest=com.example.CrashControllerTest") && line.contains(" test")));
    }

    @Test
    void runCompilesOnlyMavenModuleContainingRequiredClass() throws Exception {
        Path projectRoot = tempDir.resolve("multi-module-project");
        write(projectRoot.resolve("pom.xml"), "<project/>");
        Path moduleRoot = projectRoot.resolve("module-a");
        write(moduleRoot.resolve("pom.xml"), "<project/>");

        Path cutPath = write(
                moduleRoot.resolve("src/main/java/com/example/CrashController.java"),
                "package com.example;\npublic class CrashController {}\n"
        );
        Path outputPath = moduleRoot.resolve("src/test/java/com/example/CrashControllerTest.java");
        Path fakeMaven = writeFakeMaven(projectRoot);

        StubBackendClient backendClient = new StubBackendClient(outputFinal("package com.example; class CrashControllerTest { // PASS // HIGH_COVERAGE }\n"));
        JunitLlmWorkflowRunner workflowRunner = newWorkflowRunner(backendClient, new ProjectFileService());

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

        assertFalse(Files.exists(projectRoot.resolve("maven-commands.log")));
        List<String> moduleCommandLog = Files.readAllLines(moduleRoot.resolve("maven-commands.log"));
        assertTrue(moduleCommandLog.stream().anyMatch(line -> line.contains("test-compile")));
        assertTrue(moduleCommandLog.stream().anyMatch(line -> line.contains("verify")));
    }

    @Test
    void runContinuesWhenMissingContextCannotBeResolved() throws Exception {
        Path projectRoot = tempDir.resolve("missing-context-project");
        write(projectRoot.resolve("pom.xml"), "<project/>");
        Path cutPath = write(
                projectRoot.resolve("src/main/java/com/example/CrashController.java"),
                "package com.example;\npublic class CrashController {}\n"
        );
        Path outputPath = projectRoot.resolve("src/test/java/com/example/CrashControllerTest.java");
        Path fakeMaven = writeFakeMaven(projectRoot);

        StubBackendClient backendClient = new StubBackendClient(
                outputRequiredContext("com.example.Dependency"),
                outputFinal("package com.example; class CrashControllerTest { // PASS // HIGH_COVERAGE }\n")
        );
        JunitLlmWorkflowRunner workflowRunner = newWorkflowRunner(backendClient, new ProjectFileService());

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

        assertEquals(2, backendClient.requests.size());
        assertEquals(List.of("Class not found"), backendClient.requests.get(1).contextClasses());
    }

    @Test
    void runIgnoresUserBuildArgsForValidationAndUsesQuietBuildArgs() throws Exception {
        Path projectRoot = tempDir.resolve("no-build-args-project");
        write(projectRoot.resolve("pom.xml"), "<project/>");
        Path cutPath = write(
                projectRoot.resolve("src/main/java/com/example/CrashController.java"),
                "package com.example;\npublic class CrashController {}\n"
        );
        Path outputPath = projectRoot.resolve("src/test/java/com/example/CrashControllerTest.java");
        Path fakeMaven = writeFakeMaven(projectRoot);

        StubBackendClient backendClient = new StubBackendClient(outputFinal("""
                package com.example;
                class CrashControllerTest {
                    // PASS
                    // HIGH_COVERAGE
                }
                """));
        JunitLlmWorkflowRunner workflowRunner = newWorkflowRunner(backendClient, new ProjectFileService());

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
                List.of("-Pprofile-that-should-be-ignored", "-Dfoo=bar"),
                Duration.ofSeconds(10)
        );

        List<String> commandLog = Files.readAllLines(projectRoot.resolve("maven-commands.log"));
        assertTrue(commandLog.stream().allMatch(line -> line.contains("-q")));
        assertFalse(commandLog.stream().anyMatch(line -> line.contains("-Pprofile-that-should-be-ignored")));
        assertFalse(commandLog.stream().anyMatch(line -> line.contains("-Dfoo=bar")));
    }

    private JunitLlmWorkflowRunner newWorkflowRunner(StubBackendClient backendClient, ProjectFileService fileService) {
        JunitLlmSessionRunner sessionRunner = new JunitLlmSessionRunner(
                backendClient,
                fileService,
                new UsedContextClassPathCache(tempDir.resolve("used-context-cache.json")),
                new JunitLlmConsoleLogger(new PrintWriter(new StringWriter()))
        );
        return new JunitLlmWorkflowRunner(
                sessionRunner,
                new MavenCommandBuilder(),
                new GradleCommandBuilder(),
                new ProcessExecutor(),
                fileService
        );
    }

    private Path write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }

    private Path writeFakeMaven(Path projectRoot) throws Exception {
        Path fakeMaven = write(projectRoot.resolve("fake-mvn"), """
                #!/usr/bin/env sh
                set -eu
                LOG_FILE="$PWD/maven-commands.log"
                echo "$*" >> "$LOG_FILE"

                TEST_FILE="$PWD/src/test/java/com/example/CrashControllerTest.java"
                if echo "$*" | grep -q "dependency:sources"; then
                  mkdir -p "$PWD/src/main/java/com/example"
                  if [ ! -f "$PWD/src/main/java/com/example/Dependency.java" ]; then
                    cat > "$PWD/src/main/java/com/example/Dependency.java" <<SRC
                package com.example;
                public class Dependency {}
                SRC
                  fi
                  echo "dependency sources downloaded"
                  exit 0
                fi

                if echo "$*" | grep -q "test-compile"; then
                  if grep -q UNRELATED_BUILD_FAIL "$PWD/src/main/java/com/example/BrokenDependency.java" 2>/dev/null; then
                    echo "compilation failed due to unrelated project sources"
                    exit 1
                  fi
                  if grep -q BUILD_FAIL "$TEST_FILE" 2>/dev/null; then
                    echo "compilation failed"
                    exit 1
                  fi
                  echo "compilation ok"
                  exit 0
                fi

                if echo "$*" | grep -q " verify"; then
                  if grep -q UNRELATED_RULE_FAIL "$PWD/src/main/java/com/example/BrokenDependency.java" 2>/dev/null; then
                    echo "codebase rules failed due to unrelated project sources"
                    exit 1
                  fi
                  if grep -q RULE_FAIL "$TEST_FILE" 2>/dev/null; then
                    echo "codebase rules failed"
                    exit 1
                  fi
                  echo "rules ok"
                  exit 0
                fi

                if echo "$*" | grep -q -- "-Dtest=" && echo "$*" | grep -q " test"; then
                  if grep -q TEST_FAIL "$TEST_FILE" 2>/dev/null; then
                    echo "Tests run: 1, Failures: 1"
                    echo "AssertionFailedError: expected: <200> but was: <500>"
                    echo "Caused by: java.lang.IllegalStateException: boom"
                    exit 1
                  fi
                  echo "Tests run: 1, Failures: 0"
                  exit 0
                fi

                echo "unexpected command: $*"
                exit 2
                """);
        boolean executable = fakeMaven.toFile().setExecutable(true);
        assertTrue(executable, "fake Maven script must be executable");
        return fakeMaven;
    }

    private static FetchJobResponse.FetchJobOutput outputFinal(String finalTestFile) {
        return new FetchJobResponse.FetchJobOutput(
                "session-1",
                finalTestFile,
                List.of(),
                List.of()
        );
    }

    private static FetchJobResponse.FetchJobOutput outputRequiredContext(String requiredContextPath) {
        return new FetchJobResponse.FetchJobOutput(
                "session-1",
                "",
                List.of(requiredContextPath),
                List.of()
        );
    }

    private static final class StubBackendClient implements JunitLlmBackendClient {

        private final List<InvokeJunitLlmRequest> requests = new ArrayList<>();
        private final List<FetchJobResponse.FetchJobOutput> outputs;

        private StubBackendClient(FetchJobResponse.FetchJobOutput... outputs) {
            this.outputs = List.of(outputs);
        }

        @Override
        public InvokeJunitLlmResponse invoke(InvokeJunitLlmRequest request) {
            requests.add(request);
            return new InvokeJunitLlmResponse("job-" + requests.size(), "session-1");
        }

        @Override
        public FetchJobResponse fetchJob(String jobId) {
            int index = Integer.parseInt(jobId.substring("job-".length())) - 1;
            FetchJobResponse.FetchJobOutput output = outputs.get(Math.max(0, Math.min(index, outputs.size() - 1)));
            return new FetchJobResponse("done", output, null, null);
        }
    }
}
