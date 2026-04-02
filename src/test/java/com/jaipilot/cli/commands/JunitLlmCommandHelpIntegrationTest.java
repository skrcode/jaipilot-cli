package com.jaipilot.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jaipilot.cli.JaiPilotCli;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class JunitLlmCommandHelpIntegrationTest {

    @Test
    void generateHelpOnlyShowsInferredFriendlyOptions() {
        String helpOutput = executeHelp("generate");

        assertTrue(helpOutput.contains("--output"));
        assertTrue(helpOutput.contains("--build-executable"));
        assertTrue(helpOutput.contains("--build-arg"));
        assertTrue(helpOutput.contains("--maven-executable"));
        assertTrue(helpOutput.contains("--gradle-executable"));
        assertTrue(helpOutput.contains("--timeout-seconds"));
        assertFalse(helpOutput.contains("--attempt-number"));
        assertFalse(helpOutput.contains("--backend-url"));
        assertFalse(helpOutput.contains("--cached-context"));
        assertFalse(helpOutput.contains("--client-logs"));
        assertFalse(helpOutput.contains("--client-logs-file"));
        assertFalse(helpOutput.contains("--context"));
        assertFalse(helpOutput.contains("--cut-name"));
        assertFalse(helpOutput.contains("--jwt-token"));
        assertFalse(helpOutput.contains("--mockito-version"));
        assertFalse(helpOutput.contains("--new-testclass-file"));
        assertFalse(helpOutput.contains("--project-root"));
        assertFalse(helpOutput.contains("--session-id"));
        assertFalse(helpOutput.contains("--test-class-name"));
        assertFalse(helpOutput.contains("--verbose"));
    }

    @Test
    void rootHelpListsGenerateAndUpdateCommands() {
        StringWriter outBuffer = new StringWriter();
        CommandLine commandLine = new CommandLine(new JaiPilotCli())
                .setOut(new PrintWriter(outBuffer, true))
                .setErr(new PrintWriter(new StringWriter(), true));

        int exitCode = commandLine.execute("--help");

        assertEquals(0, exitCode);
        assertTrue(outBuffer.toString().contains("generate"));
        assertTrue(outBuffer.toString().contains("update"));
        assertFalse(outBuffer.toString().contains("verify"));
        assertFalse(outBuffer.toString().contains("fix"));
    }

    private String executeHelp(String command) {
        StringWriter outBuffer = new StringWriter();
        CommandLine commandLine = new CommandLine(new JaiPilotCli())
                .setOut(new PrintWriter(outBuffer, true))
                .setErr(new PrintWriter(new StringWriter(), true));

        int exitCode = commandLine.execute(command, "--help");

        assertEquals(0, exitCode);
        return outBuffer.toString();
    }
}
