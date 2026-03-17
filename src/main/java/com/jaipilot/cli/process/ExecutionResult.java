package com.jaipilot.cli.process;

import java.util.List;

public record ExecutionResult(
        List<String> command,
        int exitCode,
        boolean timedOut,
        String output
) {
}
