package com.jaipilot.cli.service;

import com.jaipilot.cli.backend.JunitLlmBackendClient;
import com.jaipilot.cli.files.ProjectFileService;
import com.jaipilot.cli.model.FetchJobResponse;
import com.jaipilot.cli.model.InvokeJunitLlmRequest;
import com.jaipilot.cli.model.InvokeJunitLlmResponse;
import com.jaipilot.cli.model.JunitLlmOperation;
import com.jaipilot.cli.model.JunitLlmSessionRequest;
import com.jaipilot.cli.model.JunitLlmSessionResult;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JunitLlmSessionRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void runResubmitsRequestedContextWritesFinalTestFileAndOverwritesUsedContextCache() throws Exception {
        Path cutPath = write(
                "src/main/java/com/example/CrashController.java",
                """
                package com.example;

                import com.example.support.Helper;

                public class CrashController {
                    private final Helper helper = new Helper();
                }
                """
        );
        write(
                "src/main/java/com/example/support/Helper.java",
                """
                package com.example.support;

                public class Helper {
                }
                """
        );
        write(
                "src/main/java/com/example/CachedContext.java",
                """
                package com.example;

                public class CachedContext {
                }
                """
        );
        write(
                "src/main/java/com/example/BackendUsed.java",
                """
                package com.example;

                public class BackendUsed {
                }
                """
        );
        write(
                "src/main/java/com/example/RequestedContext.java",
                """
                package com.example;

                public class RequestedContext {
                }
                """
        );

        ProjectFileService fileService = new ProjectFileService();
        UsedContextClassPathCache cache = new UsedContextClassPathCache(tempDir.resolve("used-context-cache.json"));
        cache.write(cutPath, List.of("com/example/CachedContext.java"));

        StubBackendClient backendClient = new StubBackendClient();
        StringWriter progressBuffer = new StringWriter();
        JunitLlmSessionRunner runner = new JunitLlmSessionRunner(
                backendClient,
                fileService,
                cache,
                new JunitLlmConsoleLogger(new PrintWriter(progressBuffer, true))
        );

        JunitLlmSessionResult result = runner.run(new JunitLlmSessionRequest(
                tempDir,
                cutPath,
                tempDir.resolve("src/test/java/com/example/CrashControllerTest.java"),
                JunitLlmOperation.GENERATE,
                null,
                "",
                "",
                null
        ));

        assertEquals(2, backendClient.requests.size());
        assertEquals("CrashController", backendClient.requests.get(0).cutName());
        assertEquals("CrashControllerTest", backendClient.requests.get(0).testClassName());
        assertEquals("5.11.0", backendClient.requests.get(0).mockitoVersion());
        assertEquals(
                List.of(
                        "com/example/support/Helper.java =\npackage com.example.support;\n\npublic class Helper {\n}\n",
                        "com/example/CachedContext.java =\npackage com.example;\n\npublic class CachedContext {\n}\n"
                ),
                backendClient.requests.get(0).cachedContextClasses()
        );
        assertEquals(List.of(), backendClient.requests.get(0).contextClasses());
        assertEquals("", backendClient.requests.get(0).newTestClassCode());
        assertEquals("session-1", backendClient.requests.get(1).sessionId());
        assertEquals(
                List.of(
                        "com/example/support/Helper.java =\npackage com.example.support;\n\npublic class Helper {\n}\n",
                        "com/example/BackendUsed.java =\npackage com.example;\n\npublic class BackendUsed {\n}\n"
                ),
                backendClient.requests.get(1).cachedContextClasses()
        );
        assertEquals(
                """
                package com.example;

                public class RequestedContext {
                }
                """,
                backendClient.requests.get(1).contextClasses().get(0)
        );
        assertEquals("package com.example;\nclass DraftTest {}\n", backendClient.requests.get(1).newTestClassCode());
        assertEquals("session-1", result.sessionId());
        assertEquals(
                "package com.example;\n\nclass FinalTest {\n}\n",
                Files.readString(result.outputPath())
        );
        assertEquals(List.of("com/example/support/Helper.java"), result.usedContextClassPaths());
        assertEquals(List.of("com/example/support/Helper.java"), cache.read(cutPath));
        String progressOutput = progressBuffer.toString();
        assertTrue(progressOutput.matches("(?s).*\\[\\d{2}:\\d{2}:\\d{2}\\].*"));
        assertTrue(progressOutput.contains("Generating..."));
        assertTrue(progressOutput.contains("Reading cached context for " + cutPath));
        assertTrue(progressOutput.contains("Cached context: com/example/CachedContext.java"));
        assertTrue(progressOutput.contains("Context file: com/example/RequestedContext.java"));
        assertFalse(progressOutput.contains("Submitting backend request"));
        assertFalse(progressOutput.contains("package com.example;\n\npublic class RequestedContext"));
        assertFalse(progressOutput.contains("HTTP "));
        assertFalse(progressOutput.contains("Waiting.."));
        assertFalse(progressOutput.contains("Source diff:"));
        assertFalse(progressOutput.contains("\u001B[32m+ "));
    }

    @Test
    void runFixUsesTestPathAsUsedContextCacheKey() throws Exception {
        Path cutPath = write(
                "src/main/java/com/example/CrashController.java",
                """
                package com.example;

                public class CrashController {
                }
                """
        );
        Path testPath = write(
                "src/test/java/com/example/CrashControllerTest.java",
                """
                package com.example;

                class CrashControllerTest {
                }
                """
        );
        write(
                "src/main/java/com/example/RightContext.java",
                """
                package com.example;

                public class RightContext {
                }
                """
        );
        write(
                "src/main/java/com/example/WrongContext.java",
                """
                package com.example;

                public class WrongContext {
                }
                """
        );

        UsedContextClassPathCache cache = new UsedContextClassPathCache(tempDir.resolve("used-context-cache.json"));
        cache.write(testPath, List.of("com/example/RightContext.java"));
        cache.write(cutPath, List.of("com/example/WrongContext.java"));

        StubBackendClient backendClient = new StubBackendClient(
                new FetchJobResponse(
                        "done",
                        new FetchJobResponse.FetchJobOutput(
                                "session-2",
                                "package com.example;\nclass CrashControllerTest {}\n",
                                List.of(),
                                List.of("com/example/RightContext.java")
                        ),
                        null,
                        null
                )
        );
        JunitLlmSessionRunner runner = new JunitLlmSessionRunner(
                backendClient,
                new ProjectFileService(),
                cache,
                new JunitLlmConsoleLogger(new PrintWriter(new StringWriter(), true))
        );

        runner.run(new JunitLlmSessionRequest(
                tempDir,
                cutPath,
                testPath,
                JunitLlmOperation.FIX,
                "session-2",
                Files.readString(testPath),
                "",
                "compile failed"
        ));

        assertEquals(
                List.of(
                        "com/example/RightContext.java =\npackage com.example;\n\npublic class RightContext {\n}\n"
                ),
                backendClient.requests.get(0).cachedContextClasses()
        );
    }

    @Test
    void runResubmitsRequestedContextFromTheSameModule() throws Exception {
        Files.createDirectories(tempDir.resolve("module-a"));
        Files.createDirectories(tempDir.resolve("module-b"));
        Files.writeString(tempDir.resolve("settings.gradle.kts"), "rootProject.name = \"workspace\"");
        Files.writeString(tempDir.resolve("module-a/build.gradle.kts"), "plugins { java }");
        Files.writeString(tempDir.resolve("module-b/build.gradle.kts"), "plugins { java }");
        Path cutPath = write(
                "module-b/src/main/java/com/example/CrashController.java",
                """
                package com.example;

                public class CrashController {
                }
                """
        );
        write(
                "module-a/src/main/java/com/example/RequestedContext.java",
                """
                package com.example;

                public class RequestedContext {
                    public String module() {
                        return "module-a";
                    }
                }
                """
        );
        write(
                "module-b/src/main/java/com/example/RequestedContext.java",
                """
                package com.example;

                public class RequestedContext {
                    public String module() {
                        return "module-b";
                    }
                }
                """
        );

        StubBackendClient backendClient = new StubBackendClient(
                new FetchJobResponse(
                        "done",
                        new FetchJobResponse.FetchJobOutput(
                                "session-3",
                                "package com.example;\nclass DraftTest {}\n",
                                List.of("com/example/RequestedContext.java"),
                                List.of()
                        ),
                        null,
                        null
                ),
                new FetchJobResponse(
                        "done",
                        new FetchJobResponse.FetchJobOutput(
                                "session-3",
                                "package com.example;\nclass FinalTest {}\n",
                                List.of(),
                                List.of()
                        ),
                        null,
                        null
                )
        );
        JunitLlmSessionRunner runner = new JunitLlmSessionRunner(
                backendClient,
                new ProjectFileService(),
                new UsedContextClassPathCache(tempDir.resolve("used-context-cache.json")),
                new JunitLlmConsoleLogger(new PrintWriter(new StringWriter(), true))
        );

        runner.run(new JunitLlmSessionRequest(
                tempDir,
                cutPath,
                tempDir.resolve("module-b/src/test/java/com/example/CrashControllerTest.java"),
                JunitLlmOperation.GENERATE,
                null,
                "",
                "",
                null
        ));

        assertEquals(2, backendClient.requests.size());
        assertTrue(backendClient.requests.get(1).contextClasses().get(0).contains("module-b"));
        assertFalse(backendClient.requests.get(1).contextClasses().get(0).contains("module-a"));
    }

    private Path write(String relativePath, String content) throws Exception {
        Path path = tempDir.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }

    private static final class StubBackendClient implements JunitLlmBackendClient {

        private final List<InvokeJunitLlmRequest> requests = new ArrayList<>();
        private final List<FetchJobResponse> fetchResponses;
        private int fetchCount;

        private StubBackendClient(FetchJobResponse... fetchResponses) {
            this.fetchResponses = List.of(fetchResponses);
        }

        @Override
        public InvokeJunitLlmResponse invoke(InvokeJunitLlmRequest request) {
            requests.add(request);
            String jobId = "job-" + requests.size();
            return new InvokeJunitLlmResponse(jobId, "session-1");
        }

        @Override
        public FetchJobResponse fetchJob(String jobId) {
            if (!fetchResponses.isEmpty()) {
                return fetchResponses.get(fetchCount++);
            }
            fetchCount++;
            if (fetchCount == 1) {
                return new FetchJobResponse(
                        "done",
                        new FetchJobResponse.FetchJobOutput(
                                "session-1",
                                "package com.example;\nclass DraftTest {}\n",
                                List.of("com/example/RequestedContext.java"),
                                List.of("com/example/BackendUsed.java")
                        ),
                        null,
                        null
                );
            }
            return new FetchJobResponse(
                    "done",
                    new FetchJobResponse.FetchJobOutput(
                            "session-1",
                                "package com.example;\nclass FinalTest {}\n",
                                List.of(),
                                List.of("com/example/support/Helper.java")
                        ),
                        null,
                        null
            );
        }
    }
}
