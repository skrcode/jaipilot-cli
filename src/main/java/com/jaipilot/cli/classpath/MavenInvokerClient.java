package com.jaipilot.cli.classpath;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.invoker.MavenInvocationException;

final class MavenInvokerClient {

    MavenExecutionResult execute(Path moduleRoot, Path executable, List<String> goals, List<String> additionalArgs)
            throws MavenInvocationException {
        List<String> stdout = new CopyOnWriteArrayList<>();
        List<String> stderr = new CopyOnWriteArrayList<>();

        InvocationRequest request = new DefaultInvocationRequest();
        request.setBaseDirectory(moduleRoot.toFile());
        request.setBatchMode(true);
        request.setNoTransferProgress(true);
        request.setShowErrors(true);
        request.setGoals(goals);
        if (additionalArgs != null && !additionalArgs.isEmpty()) {
            request.addArgs(additionalArgs);
        }
        request.setOutputHandler(stdout::add);
        request.setErrorHandler(stderr::add);

        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isBlank()) {
            request.setJavaHome(new File(javaHome));
        }
        if (executable != null) {
            request.setMavenExecutable(executable.toFile());
        }

        Invoker invoker = new DefaultInvoker();
        InvocationResult result = invoker.execute(request);
        return new MavenExecutionResult(
                result.getExitCode(),
                List.copyOf(stdout),
                List.copyOf(stderr),
                result.getExecutionException()
        );
    }

    record MavenExecutionResult(
            int exitCode,
            List<String> stdout,
            List<String> stderr,
            CommandLineException executionException
    ) {

        MavenExecutionResult(int exitCode, List<String> stdout, List<String> stderr, Exception executionException) {
            this(
                    exitCode,
                    stdout == null ? List.of() : stdout,
                    stderr == null ? List.of() : stderr,
                    executionException == null ? null : new CommandLineException(executionException)
            );
        }

        boolean isSuccessful() {
            return executionException == null && exitCode == 0;
        }
    }

    record CommandLineException(Exception cause) {
    }
}
