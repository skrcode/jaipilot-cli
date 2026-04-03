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
    void runSucceedsWhenGeneratedTestPassesCompilationSanity() throws Exception {
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

        List<String> commandLog = Files.readAllLines(projectRoot.resolve("maven-commands.log"));
        assertEquals(2, commandLog.size());
        assertTrue(commandLog.stream().allMatch(line -> line.contains("test-compile")));
        assertFalse(commandLog.stream().anyMatch(line -> line.contains("verify")));
        assertFalse(commandLog.stream().anyMatch(line -> line.contains("jacoco")));
    }

    @Test
    void runFixesGeneratedTestWhenCompilationFails() throws Exception {
        Path projectRoot = tempDir.resolve("compile-fix-project");
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
                            // BUILD_FAIL
                        }
                        """),
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
        assertTrue(backendClient.requests.get(1).clientLogs().contains("Failed phase: test-compile"));
        assertTrue(Files.readString(outputPath).contains("PASS"));

        List<String> commandLog = Files.readAllLines(projectRoot.resolve("maven-commands.log"));
        assertEquals(3, commandLog.size());
        assertTrue(commandLog.stream().allMatch(line -> line.contains("test-compile")));
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

        List<String> commandLog = Files.readAllLines(projectRoot.resolve("maven-commands.log"));
        assertEquals(1, commandLog.size());
    }

    @Test
    void runSkipsPostGenerationCompilationWhenTestIsUnchanged() throws Exception {
        Path projectRoot = tempDir.resolve("unchanged-test-project");
        write(projectRoot.resolve("pom.xml"), "<project/>");
        Path cutPath = write(
                projectRoot.resolve("src/main/java/com/example/CrashController.java"),
                "package com.example;\npublic class CrashController {}\n"
        );
        Path outputPath = write(
                projectRoot.resolve("src/test/java/com/example/CrashControllerTest.java"),
                "package com.example;\nclass CrashControllerTest { // PASS }\n"
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
        assertEquals(1, commandLog.size());
        assertTrue(commandLog.get(0).contains("test-compile"));
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

        StubBackendClient backendClient = new StubBackendClient(outputFinal("package com.example; class CrashControllerTest { // PASS }\n"));
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
        assertEquals(2, moduleCommandLog.size());
        assertTrue(moduleCommandLog.stream().allMatch(line -> line.contains("test-compile")));
    }

    @Test
    void runFailsFastWhenMissingContextCannotBeResolved() throws Exception {
        Path projectRoot = tempDir.resolve("missing-context-project");
        write(projectRoot.resolve("pom.xml"), "<project/>");
        Path cutPath = write(
                projectRoot.resolve("src/main/java/com/example/CrashController.java"),
                "package com.example;\npublic class CrashController {}\n"
        );
        Path outputPath = projectRoot.resolve("src/test/java/com/example/CrashControllerTest.java");
        Path fakeMaven = writeFakeMaven(projectRoot);

        StubBackendClient backendClient = new StubBackendClient(
                outputRequiredContext("com/example/Dependency.java"),
                outputFinal("package com.example; class CrashControllerTest { // PASS }\n")
        );
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

        assertTrue(exception.getMessage().contains("Unable to resolve requested context class path com/example/Dependency.java"));
        assertEquals(1, backendClient.requests.size());

        List<String> commandLog = Files.readAllLines(projectRoot.resolve("maven-commands.log"));
        assertEquals(1, commandLog.size());
        assertTrue(commandLog.stream().allMatch(line -> line.contains("test-compile")));
        assertFalse(commandLog.stream().anyMatch(line -> line.contains("dependency:sources")));
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

                case "$*" in
                  *dependency:sources*)
                    mkdir -p "$PWD/src/main/java/com/example"
                    if [ ! -f "$PWD/src/main/java/com/example/Dependency.java" ]; then
                      cat > "$PWD/src/main/java/com/example/Dependency.java" <<SRC
                package com.example;
                public class Dependency {}
                SRC
                    fi
                    echo "dependency sources downloaded"
                    exit 0
                    ;;

                  *test-compile*)
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
