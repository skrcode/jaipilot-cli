package com.jaipilot.cli.service;

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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JunitLlmWorkflowRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void runBuildsExecutesAndFixesUntilTargetTestPasses() throws Exception {
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

        StubBackendClient backendClient = new StubBackendClient();
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

        JunitLlmSessionResult result = workflowRunner.run(new JunitLlmSessionRequest(
                projectRoot,
                cutPath,
                outputPath,
                JunitLlmOperation.GENERATE,
                null,
                "",
                "",
                null
        ), fakeMaven, List.of("-Drepo.password=hunter2"), Duration.ofSeconds(10), 5, 0.0d);

        assertEquals("session-1", result.sessionId());
        assertTrue(Files.readString(outputPath).contains("PASS"));
        assertEquals(3, backendClient.requests.size());
        InvokeJunitLlmRequest firstFixRequest = backendClient.requests.get(1);
        InvokeJunitLlmRequest secondFixRequest = backendClient.requests.get(2);
        assertEquals("generate", backendClient.requests.get(0).type());
        assertEquals("fix", firstFixRequest.type());
        assertEquals("fix", secondFixRequest.type());
        assertEquals("", firstFixRequest.cutName());
        assertEquals("", firstFixRequest.cutCode());
        assertTrue(firstFixRequest.clientLogs().contains("Phase: test-compile"));
        assertTrue(firstFixRequest.initialTestClassCode().contains("BUILD_FAIL"));
        assertEquals("", firstFixRequest.newTestClassCode());
        assertTrue(firstFixRequest.clientLogs().contains("-Drepo.password=[REDACTED]"));
        assertTrue(firstFixRequest.clientLogs().contains("Authorization: Bearer [REDACTED]"));
        assertTrue(firstFixRequest.clientLogs().contains("refresh_token=[REDACTED]"));
        assertTrue(firstFixRequest.clientLogs().contains("JAIPILOT_JWT_TOKEN=[REDACTED]"));
        assertFalse(firstFixRequest.clientLogs().contains("hunter2"));
        assertFalse(firstFixRequest.clientLogs().contains("compile-secret-token"));
        assertEquals("", secondFixRequest.cutName());
        assertEquals("", secondFixRequest.cutCode());
        assertTrue(secondFixRequest.clientLogs().contains("Phase: test"));
        assertTrue(secondFixRequest.initialTestClassCode().contains("TEST_FAIL"));
        assertEquals("", secondFixRequest.newTestClassCode());
        assertTrue(secondFixRequest.clientLogs().contains("Authorization: Bearer [REDACTED]"));
        assertTrue(secondFixRequest.clientLogs().contains("refresh_token=[REDACTED]"));
        assertTrue(secondFixRequest.clientLogs().contains("JAIPILOT_JWT_TOKEN=[REDACTED]"));

        List<String> commandLog = Files.readAllLines(projectRoot.resolve("maven-commands.log"));
        assertEquals(6, commandLog.size());
        assertTrue(commandLog.get(0).contains("test-compile"));
        assertTrue(commandLog.get(1).contains("test-compile"));
        assertTrue(commandLog.get(2).contains("test-compile"));
        assertTrue(commandLog.get(3).contains("-Dtest=com.example.CrashControllerTest"));
        assertTrue(commandLog.get(4).contains("test-compile"));
        assertTrue(commandLog.get(5).contains("-Dtest=com.example.CrashControllerTest"));
    }

    @Test
    void runBuildsUsesGradleForGradleProjects() throws Exception {
        Path projectRoot = tempDir.resolve("gradle-project");
        write(projectRoot.resolve("build.gradle.kts"), "plugins { java }");
        Path cutPath = write(
                projectRoot.resolve("src/main/java/com/example/CrashController.java"),
                """
                package com.example;

                public class CrashController {
                }
                """
        );
        Path outputPath = projectRoot.resolve("src/test/java/com/example/CrashControllerTest.java");
        Path fakeGradle = writeFakeGradle(projectRoot);

        StubBackendClient backendClient = new StubBackendClient();
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

        JunitLlmSessionResult result = workflowRunner.run(new JunitLlmSessionRequest(
                projectRoot,
                cutPath,
                outputPath,
                JunitLlmOperation.GENERATE,
                null,
                "",
                "",
                null
        ), fakeGradle, List.of("-PrepoPassword=hunter2"), Duration.ofSeconds(10), 5, 0.0d);

        assertEquals("session-1", result.sessionId());
        assertTrue(Files.readString(outputPath).contains("PASS"));
        List<String> commandLog = Files.readAllLines(projectRoot.resolve("gradle-commands.log"));
        assertEquals(6, commandLog.size());
        assertTrue(commandLog.get(0).contains("testClasses"));
        assertTrue(commandLog.get(3).contains("test --tests com.example.CrashControllerTest"));
        assertTrue(commandLog.get(5).contains("test --tests com.example.CrashControllerTest"));
    }

    @Test
    void runAllowsExistingTargetTestCompileFailureDuringPreflight() throws Exception {
        Path projectRoot = tempDir.resolve("preflight-project");
        write(projectRoot.resolve("pom.xml"), "<project/>");
        Path cutPath = write(
                projectRoot.resolve("src/main/java/com/example/CrashController.java"),
                """
                package com.example;

                public class CrashController {
                }
                """
        );
        Path outputPath = write(
                projectRoot.resolve("src/test/java/com/example/CrashControllerTest.java"),
                """
                package com.example;

                class CrashControllerTest {
                    // BUILD_FAIL
                }
                """
        );
        Path fakeMaven = writeFakeMaven(projectRoot);

        StubBackendClient backendClient = new StubBackendClient();
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

        JunitLlmSessionResult result = workflowRunner.run(new JunitLlmSessionRequest(
                projectRoot,
                cutPath,
                outputPath,
                JunitLlmOperation.GENERATE,
                null,
                Files.readString(outputPath),
                "",
                null
        ), fakeMaven, List.of(), Duration.ofSeconds(10), 5, 0.0d);

        assertEquals("session-1", result.sessionId());
        assertTrue(Files.readString(outputPath).contains("PASS"));
        assertEquals(3, backendClient.requests.size());
    }

    @Test
    void runStopsBeforeBackendWhenProjectHasUnrelatedCompileFailure() throws Exception {
        Path projectRoot = tempDir.resolve("broken-project");
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

                class BrokenDependency {
                    // UNRELATED_BUILD_FAIL
                }
                """
        );
        Path outputPath = projectRoot.resolve("src/test/java/com/example/CrashControllerTest.java");
        Path fakeMaven = writeFakeMaven(projectRoot);

        StubBackendClient backendClient = new StubBackendClient();
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

        IllegalStateException exception = org.junit.jupiter.api.Assertions.assertThrows(
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
                        Duration.ofSeconds(10),
                        5,
                        0.0d
                )
        );

        assertTrue(exception.getMessage().contains("Local build failed before JAIPilot started"));
        assertEquals(0, backendClient.requests.size());
    }

    @Test
    void runKeepsFixingUntilCoverageThresholdIsMet() throws Exception {
        Path projectRoot = tempDir.resolve("coverage-project");
        write(projectRoot.resolve("pom.xml"), "<project/>");
        Path cutPath = write(
                projectRoot.resolve("src/main/java/com/example/CrashController.java"),
                """
                package com.example;

                public class CrashController {
                    int adjust(int value) {
                        if (value > 10) {
                            return value - 1;
                        }
                        return value + 1;
                    }
                }
                """
        );
        Path outputPath = projectRoot.resolve("src/test/java/com/example/CrashControllerTest.java");
        Path fakeMaven = writeFakeMaven(projectRoot);

        StubBackendClient backendClient = new StubBackendClient(
                """
                package com.example;

                class CrashControllerTest {
                    void generated() {
                        // LOW_COVERAGE
                    }
                }
                """,
                """
                package com.example;

                class CrashControllerTest {
                    void generated() {
                        // HIGH_COVERAGE
                    }
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

        JunitLlmSessionResult result = workflowRunner.run(new JunitLlmSessionRequest(
                projectRoot,
                cutPath,
                outputPath,
                JunitLlmOperation.GENERATE,
                null,
                "",
                "",
                null
        ), fakeMaven, List.of(), Duration.ofSeconds(10), 5, 80.0d);

        assertEquals("session-1", result.sessionId());
        assertEquals(2, backendClient.requests.size());
        assertEquals("generate", backendClient.requests.get(0).type());
        assertEquals("fix", backendClient.requests.get(1).type());
        assertTrue(backendClient.requests.get(1).clientLogs().contains("Phase: coverage"));
        assertTrue(backendClient.requests.get(1).clientLogs().contains("Coverage threshold: 80.00%"));
        assertTrue(backendClient.requests.get(1).clientLogs().contains("Actual CUT line coverage: 50.00%"));
        assertTrue(Files.readString(outputPath).contains("class CrashControllerTest {"));
    }

    private Path write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }

    private Path writeFakeMaven(Path projectRoot) throws Exception {
        Path fakeMaven = projectRoot.resolve("mvnw");
        Files.createDirectories(projectRoot);
        Files.writeString(fakeMaven, """
                #!/usr/bin/env sh
                set -eu

                TEST_FILE="$PWD/src/test/java/com/example/CrashControllerTest.java"
                printf '%s\\n' "$*" >> "$PWD/maven-commands.log"

                case "$*" in
                  *test-compile*)
                    if grep -q UNRELATED_BUILD_FAIL "$PWD/src/main/java/com/example/BrokenDependency.java" 2>/dev/null; then
                      echo "compile failed: unrelated dependency"
                      exit 1
                    fi
                    if grep -q BUILD_FAIL "$TEST_FILE"; then
                      echo "compile failed: missing symbol"
                      echo "Authorization: Bearer compile-secret-token"
                      echo "refresh_token=refresh-compile-token"
                      echo "JAIPILOT_JWT_TOKEN=jwt-compile-token"
                      exit 1
                    fi
                    echo "compile ok"
                    exit 0
                    ;;
                  *jacoco-maven-plugin*prepare-agent*test*jacoco-maven-plugin*report*)
                    mkdir -p "$PWD/target/site/jacoco"
                    if grep -q LOW_COVERAGE "$TEST_FILE"; then
                      cat > "$PWD/target/site/jacoco/jacoco.xml" <<'EOF'
                <report name="JAIPilot">
                  <package name="com/example">
                    <class name="com/example/CrashController" sourcefilename="CrashController.java">
                      <method name="adjust" desc="(I)I" line="4">
                        <counter type="LINE" missed="1" covered="1"/>
                        <counter type="BRANCH" missed="1" covered="1"/>
                        <counter type="INSTRUCTION" missed="1" covered="1"/>
                      </method>
                      <counter type="LINE" missed="1" covered="1"/>
                      <counter type="BRANCH" missed="1" covered="1"/>
                      <counter type="INSTRUCTION" missed="1" covered="1"/>
                    </class>
                    <sourcefile name="CrashController.java">
                      <line nr="4" mi="0" ci="1" mb="0" cb="0"/>
                      <line nr="5" mi="1" ci="0" mb="1" cb="0"/>
                      <counter type="LINE" missed="1" covered="1"/>
                      <counter type="BRANCH" missed="1" covered="1"/>
                      <counter type="INSTRUCTION" missed="1" covered="1"/>
                    </sourcefile>
                  </package>
                  <counter type="LINE" missed="1" covered="1"/>
                  <counter type="BRANCH" missed="1" covered="1"/>
                  <counter type="INSTRUCTION" missed="1" covered="1"/>
                </report>
                EOF
                    else
                      cat > "$PWD/target/site/jacoco/jacoco.xml" <<'EOF'
                <report name="JAIPilot">
                  <package name="com/example">
                    <class name="com/example/CrashController" sourcefilename="CrashController.java">
                      <method name="adjust" desc="(I)I" line="4">
                        <counter type="LINE" missed="0" covered="2"/>
                        <counter type="BRANCH" missed="0" covered="2"/>
                        <counter type="INSTRUCTION" missed="0" covered="2"/>
                      </method>
                      <counter type="LINE" missed="0" covered="2"/>
                      <counter type="BRANCH" missed="0" covered="2"/>
                      <counter type="INSTRUCTION" missed="0" covered="2"/>
                    </class>
                    <sourcefile name="CrashController.java">
                      <line nr="4" mi="0" ci="1" mb="0" cb="0"/>
                      <line nr="5" mi="0" ci="1" mb="0" cb="1"/>
                      <counter type="LINE" missed="0" covered="2"/>
                      <counter type="BRANCH" missed="0" covered="2"/>
                      <counter type="INSTRUCTION" missed="0" covered="2"/>
                    </sourcefile>
                  </package>
                  <counter type="LINE" missed="0" covered="2"/>
                  <counter type="BRANCH" missed="0" covered="2"/>
                  <counter type="INSTRUCTION" missed="0" covered="2"/>
                </report>
                EOF
                    fi
                    echo "coverage ok"
                    exit 0
                    ;;
                  *-Dtest=com.example.CrashControllerTest*test)
                    if grep -q TEST_FAIL "$TEST_FILE"; then
                      echo "Tests run: 1, Failures: 1"
                      echo "Authorization: Bearer test-secret-token"
                      echo "refresh_token=refresh-test-token"
                      echo "JAIPILOT_JWT_TOKEN=jwt-test-token"
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

    private Path writeFakeGradle(Path projectRoot) throws Exception {
        Path fakeGradle = projectRoot.resolve("gradlew");
        Files.createDirectories(projectRoot);
        Files.writeString(fakeGradle, """
                #!/usr/bin/env sh
                set -eu

                TEST_FILE="$PWD/src/test/java/com/example/CrashControllerTest.java"
                printf '%s\\n' "$*" >> "$PWD/gradle-commands.log"

                case "$*" in
                  *testClasses*)
                    if grep -q BUILD_FAIL "$TEST_FILE"; then
                      echo "compile failed: missing symbol"
                      echo "Authorization: Bearer compile-secret-token"
                      echo "refresh_token=refresh-compile-token"
                      echo "JAIPILOT_JWT_TOKEN=jwt-compile-token"
                      exit 1
                    fi
                    echo "compile ok"
                    exit 0
                    ;;
                  *test*--tests*com.example.CrashControllerTest*)
                    if grep -q TEST_FAIL "$TEST_FILE"; then
                      echo "Tests run: 1, Failures: 1"
                      echo "Authorization: Bearer test-secret-token"
                      echo "refresh_token=refresh-test-token"
                      echo "JAIPILOT_JWT_TOKEN=jwt-test-token"
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
        boolean executable = fakeGradle.toFile().setExecutable(true);
        assertTrue(executable, "fake Gradle script must be executable");
        return fakeGradle;
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
            if (!finalTestFiles.isEmpty()) {
                int index = Integer.parseInt(jobId.substring("job-".length())) - 1;
                if (index >= 0 && index < finalTestFiles.size()) {
                    return doneResponse(finalTestFiles.get(index));
                }
            }
            return switch (jobId) {
                case "job-1" -> doneResponse("""
                        package com.example;

                        class CrashControllerTest {
                            // BUILD_FAIL
                        }
                        """);
                case "job-2" -> doneResponse("""
                        package com.example;

                        class CrashControllerTest {
                            // TEST_FAIL
                        }
                        """);
                case "job-3" -> doneResponse("""
                        package com.example;

                        class CrashControllerTest {
                            // PASS
                        }
                        """);
                default -> throw new IllegalStateException("Unexpected job id " + jobId);
            };
        }

        private FetchJobResponse doneResponse(String finalTestFile) {
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
