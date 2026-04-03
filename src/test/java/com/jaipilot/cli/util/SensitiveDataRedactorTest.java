package com.jaipilot.cli.util;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveDataRedactorTest {

    @Test
    void redactMasksCommonSecretFormats() {
        String original = """
                Authorization: Bearer abc.def.ghi
                refresh_token=refresh-secret
                {"password":"super-secret","access_token":"jwt-secret"}
                https://user:plain-text-password@example.com/repo.git
                """;

        String redacted = SensitiveDataRedactor.redact(original);

        assertTrue(redacted.contains("Authorization: Bearer [REDACTED]"));
        assertTrue(redacted.contains("refresh_token=[REDACTED]"));
        assertTrue(redacted.contains("\"password\":\"[REDACTED]\""));
        assertTrue(redacted.contains("\"access_token\":\"[REDACTED]\""));
        assertTrue(redacted.contains("https://user:[REDACTED]@example.com/repo.git"));
        assertFalse(redacted.contains("plain-text-password"));
        assertFalse(redacted.contains("refresh-secret"));
        assertFalse(redacted.contains("super-secret"));
        assertFalse(redacted.contains("jwt-secret"));
    }

    @Test
    void redactCommandMasksSensitiveFlagsAndSystemProperties() {
        String redacted = SensitiveDataRedactor.redactCommand(List.of(
                "./mvnw",
                "-Drepo.password=hunter2",
                "--jwt-token",
                "abc123"
        ));

        assertEquals("./mvnw -Drepo.password=[REDACTED] --jwt-token [REDACTED]", redacted);
    }

    @Test
    void redactBuildOutputKeepsFailureSignalAndTrimsStackTraceNoise() {
        String output = """
                [ERROR] Tests run: 1, Failures: 1
                org.opentest4j.AssertionFailedError: expected: <200> but was: <500>
                \tat org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
                \tat org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
                \tat com.example.CrashControllerTest.shouldReturnOk(CrashControllerTest.java:42)
                \t... 23 more
                Caused by: java.lang.IllegalStateException: boom
                \tat com.example.CrashController.handle(CrashController.java:17)
                """;

        String redacted = SensitiveDataRedactor.redactBuildOutput(output);

        assertTrue(redacted.contains("Tests run: 1, Failures: 1"));
        assertTrue(redacted.contains("AssertionFailedError: expected: <200> but was: <500>"));
        assertTrue(redacted.contains("Caused by: java.lang.IllegalStateException: boom"));
        assertTrue(redacted.contains("[stack trace trimmed:"));
        assertFalse(redacted.contains("AssertionFailureBuilder.build("));
        assertFalse(redacted.contains("CrashControllerTest.shouldReturnOk"));
        assertFalse(redacted.contains("... 23 more"));
    }

    @Test
    void redactBuildOutputExcludesWarningsButKeepsCompileAndAssertionErrors() {
        String output = """
                [WARNING] /repo/src/main/java/com/example/Demo.java:[12,1] Generating equals/hashCode implementation
                [ERROR] /repo/src/main/java/com/example/Demo.java:[20,17] cannot find symbol
                symbol:   class MissingType
                location: class com.example.Demo
                [ERROR] Tests run: 1, Failures: 1
                org.opentest4j.AssertionFailedError: expected: <200> but was: <500>
                """;

        String redacted = SensitiveDataRedactor.redactBuildOutput(output);

        assertTrue(redacted.contains("cannot find symbol"));
        assertTrue(redacted.contains("symbol:   class MissingType"));
        assertTrue(redacted.contains("location: class com.example.Demo"));
        assertTrue(redacted.contains("AssertionFailedError: expected: <200> but was: <500>"));
        assertFalse(redacted.contains("[WARNING]"));
        assertFalse(redacted.contains("Generating equals/hashCode"));
    }

    @Test
    void redactBuildOutputReturnsExplicitMessageWhenOnlyWarningsArePresent() {
        String output = """
                [WARNING] /repo/src/main/java/com/example/Demo.java:[12,1] Some warning
                WARNING: A Java agent has been loaded dynamically
                """;

        String redacted = SensitiveDataRedactor.redactBuildOutput(output);

        assertEquals("Build failed but no explicit error lines were captured.", redacted);
    }
}
