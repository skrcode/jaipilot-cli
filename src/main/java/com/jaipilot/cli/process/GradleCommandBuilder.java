package com.jaipilot.cli.process;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class GradleCommandBuilder implements LocalBuildCommandBuilder {

    @Override
    public BuildTool buildTool() {
        return BuildTool.GRADLE;
    }

    public List<String> buildVerification(
            Path projectRoot,
            Path explicitGradleExecutable,
            List<String> additionalArguments,
            boolean skipClean
    ) {
        List<String> command = baseCommand(projectRoot, explicitGradleExecutable, additionalArguments);
        if (!skipClean) {
            command.add("clean");
        }
        command.add("test");
        command.add("jacocoTestReport");
        command.add("pitest");
        return command;
    }

    @Override
    public List<String> buildTestCompile(
            Path projectRoot,
            Path explicitGradleExecutable,
            List<String> additionalArguments
    ) {
        List<String> command = baseCommand(projectRoot, explicitGradleExecutable, additionalArguments);
        command.add("testClasses");
        return command;
    }

    @Override
    public List<String> buildSingleTestExecution(
            Path projectRoot,
            Path explicitGradleExecutable,
            List<String> additionalArguments,
            String testSelector
    ) {
        List<String> command = baseCommand(projectRoot, explicitGradleExecutable, additionalArguments);
        command.add("test");
        command.add("--tests");
        command.add(testSelector);
        return command;
    }

    public List<String> buildSingleTestCoverage(
            Path projectRoot,
            Path explicitGradleExecutable,
            List<String> additionalArguments,
            String testSelector,
            Path initScriptPath
    ) {
        List<String> command = baseCommand(projectRoot, explicitGradleExecutable, additionalArguments);
        command.add("-I");
        command.add(initScriptPath.toString());
        command.add("test");
        command.add("--tests");
        command.add(testSelector);
        command.add("jacocoTestReport");
        return command;
    }

    String resolveGradleExecutable(Path buildRoot, Path explicitGradleExecutable) {
        if (explicitGradleExecutable != null) {
            return toExecutablePath(buildRoot, explicitGradleExecutable);
        }

        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
        String wrapperName = windows ? "gradlew.bat" : "gradlew";
        Path wrapperPath = findWrapper(buildRoot, wrapperName);
        if (wrapperPath != null) {
            return wrapperPath.toString();
        }
        return windows ? "gradle.bat" : "gradle";
    }

    private List<String> baseCommand(
            Path buildRoot,
            Path explicitGradleExecutable,
            List<String> additionalArguments
    ) {
        List<String> command = new ArrayList<>();
        command.add(resolveGradleExecutable(buildRoot, explicitGradleExecutable));
        command.add("--no-daemon");
        command.add("--console=plain");
        command.addAll(additionalArguments);
        return command;
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

    private Path findWrapper(Path buildRoot, String wrapperName) {
        Path current = buildRoot.normalize();
        while (current != null) {
            Path wrapperPath = current.resolve(wrapperName);
            if (Files.isRegularFile(wrapperPath)) {
                return wrapperPath;
            }
            current = current.getParent();
        }
        return null;
    }
}
