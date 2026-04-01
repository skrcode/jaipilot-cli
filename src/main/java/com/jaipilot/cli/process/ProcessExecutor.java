package com.jaipilot.cli.process;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ProcessExecutor {

    private static final long HEARTBEAT_SECONDS = 5L;

    public ExecutionResult execute(
            List<String> command,
            Path workingDirectory,
            Duration timeout,
            boolean verbose,
            PrintWriter verboseWriter
    ) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true);

        Process process = processBuilder.start();
        StringBuilder output = new StringBuilder();
        Thread readerThread = new Thread(() -> readOutput(process, output, verbose, verboseWriter), "jaipilot-process-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        boolean finished = waitForProcess(process, timeout, verboseWriter);
        if (!finished) {
            process.destroyForcibly();
        }

        readerThread.join(TimeUnit.SECONDS.toMillis(5));
        int exitCode = finished ? process.exitValue() : -1;
        return new ExecutionResult(command, exitCode, !finished, output.toString());
    }

    private boolean waitForProcess(Process process, Duration timeout, PrintWriter progressWriter) throws InterruptedException {
        long timeoutMillis = timeout.toMillis();
        long elapsedMillis = 0L;
        while (elapsedMillis < timeoutMillis) {
            long waitMillis = Math.min(TimeUnit.SECONDS.toMillis(HEARTBEAT_SECONDS), timeoutMillis - elapsedMillis);
            if (process.waitFor(waitMillis, TimeUnit.MILLISECONDS)) {
                return true;
            }
            elapsedMillis += waitMillis;
            progressWriter.println("PROGRESS: Build is still running (" + TimeUnit.MILLISECONDS.toSeconds(elapsedMillis)
                    + "s elapsed)");
            progressWriter.flush();
        }
        return false;
    }

    private static void readOutput(Process process, StringBuilder output, boolean verbose, PrintWriter verboseWriter) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
                if (verbose) {
                    verboseWriter.println(line);
                }
            }
            if (verbose) {
                verboseWriter.flush();
            }
        } catch (IOException exception) {
            output.append("Failed to read process output: ")
                    .append(exception.getMessage())
                    .append(System.lineSeparator());
        }
    }
}
