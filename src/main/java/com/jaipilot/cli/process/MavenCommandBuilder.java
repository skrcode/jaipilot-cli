package com.jaipilot.cli.process;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MavenCommandBuilder implements LocalBuildCommandBuilder {

    @Override
    public BuildTool buildTool() {
        return BuildTool.MAVEN;
    }

    @Override
    public List<String> buildTestCompile(
            Path projectRoot,
            Path explicitMavenExecutable,
            List<String> additionalArguments
    ) {
        List<String> command = baseCommand(projectRoot, explicitMavenExecutable, additionalArguments);
        command.add("-DskipTests");
        command.add("test-compile");
        return command;
    }

    @Override
    public List<String> buildSingleTestExecution(
            Path projectRoot,
            Path explicitMavenExecutable,
            List<String> additionalArguments,
            String testSelector
    ) {
        List<String> command = baseCommand(projectRoot, explicitMavenExecutable, additionalArguments);
        command.add("-DskipTests=false");
        command.add("-DfailIfNoTests=false");
        command.add("-Dsurefire.failIfNoSpecifiedTests=false");
        command.add("-Dtest=" + testSelector);
        command.add("test");
        return command;
    }

    public List<String> buildDependencySourcesDownload(
            Path projectRoot,
            Path explicitMavenExecutable,
            List<String> additionalArguments
    ) {
        List<String> command = baseCommand(projectRoot, explicitMavenExecutable, additionalArguments);
        command.add("-DskipTests");
        command.add("dependency:sources");
        return command;
    }

    String resolveMavenExecutable(Path buildRoot, Path explicitMavenExecutable) {
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
        if (explicitMavenExecutable != null) {
            String explicitExecutable = toExecutablePath(buildRoot, explicitMavenExecutable);
            return normalizeWrapperExecutable(explicitExecutable, windows);
        }

        String wrapperName = windows ? "mvnw.cmd" : "mvnw";
        Path wrapperPath = findWrapper(buildRoot, wrapperName);
        if (wrapperPath != null) {
            return wrapperPath.toString();
        }
        return windows ? "mvn.cmd" : "mvn";
    }

    private static String toExecutablePath(Path buildRoot, Path executable) {
        if (executable.isAbsolute()) {
            return executable.toString();
        }
        Path projectRelative = buildRoot.resolve(executable).normalize();
        if (Files.exists(projectRelative)) {
            return projectRelative.toString();
        }
        return executable.toString();
    }

    private List<String> baseCommand(
            Path buildRoot,
            Path explicitMavenExecutable,
            List<String> additionalArguments
    ) {
        List<String> command = new ArrayList<>();
        command.add(resolveMavenExecutable(buildRoot, explicitMavenExecutable));
        command.add("-B");
        command.add("-ntp");
        command.addAll(additionalArguments);
        return command;
    }

    private Path findWrapper(Path buildRoot, String wrapperName) {
        Path current = buildRoot.normalize();
        while (current != null) {
            Path wrapperPath = current.resolve(wrapperName);
            if (Files.isRegularFile(wrapperPath) && hasWrapperProperties(current)) {
                return wrapperPath;
            }
            current = current.getParent();
        }
        return null;
    }

    private String normalizeWrapperExecutable(String executable, boolean windows) {
        if (isWrapperPath(executable) && !hasWrapperPropertiesForExecutable(executable)) {
            return windows ? "mvn.cmd" : "mvn";
        }
        return executable;
    }

    private boolean isWrapperPath(String executable) {
        String normalized = executable.toLowerCase(Locale.ROOT);
        return normalized.endsWith("/mvnw")
                || normalized.endsWith("\\mvnw")
                || normalized.equals("mvnw")
                || normalized.endsWith("/mvnw.cmd")
                || normalized.endsWith("\\mvnw.cmd")
                || normalized.equals("mvnw.cmd");
    }

    private boolean hasWrapperPropertiesForExecutable(String executable) {
        try {
            Path executablePath = Path.of(executable);
            if (!Files.isRegularFile(executablePath)) {
                return true;
            }
            Path parent = executablePath.getParent();
            return parent == null || hasWrapperProperties(parent);
        } catch (Exception ignored) {
            return true;
        }
    }

    private boolean hasWrapperProperties(Path wrapperRoot) {
        return Files.isRegularFile(wrapperRoot.resolve(".mvn/wrapper/maven-wrapper.properties"));
    }
}
