package com.jaipilot.cli.classpath;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.gradle.tooling.BuildLauncher;
import org.gradle.tooling.GradleConnectionException;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;

final class GradleToolingClient {

    GradleExecutionResult run(Path projectRoot, List<String> tasks, List<String> arguments) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try {
            GradleConnector connector = GradleConnector.newConnector()
                    .forProjectDirectory(projectRoot.toFile());
            try (ProjectConnection connection = connector.connect()) {
                BuildLauncher launcher = connection.newBuild();
                launcher.forTasks(tasks.toArray(String[]::new));
                if (arguments != null && !arguments.isEmpty()) {
                    launcher.withArguments(arguments.toArray(String[]::new));
                }
                launcher.setStandardOutput(stdout);
                launcher.setStandardError(stderr);
                launcher.run();
            }
            return new GradleExecutionResult(true, stdout, stderr, null);
        } catch (GradleConnectionException exception) {
            return new GradleExecutionResult(false, stdout, stderr, exception);
        }
    }

    record GradleExecutionResult(boolean success, String stdout, String stderr, Exception exception) {

        GradleExecutionResult(boolean success, ByteArrayOutputStream stdout, ByteArrayOutputStream stderr, Exception exception) {
            this(
                    success,
                    stdout == null ? "" : stdout.toString(StandardCharsets.UTF_8),
                    stderr == null ? "" : stderr.toString(StandardCharsets.UTF_8),
                    exception
            );
        }
    }
}
