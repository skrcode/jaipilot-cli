package com.jaipilot.cli.process;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MavenCommandBuilder {

    public List<String> build(
            Path buildRoot,
            Path buildPomPath,
            Path explicitMavenExecutable,
            List<String> additionalArguments,
            String jacocoVersion,
            String pitVersion,
            boolean skipClean,
            boolean runAggregateCoverage
    ) {
        List<String> command = new ArrayList<>();
        command.add(resolveMavenExecutable(buildRoot, explicitMavenExecutable));
        command.add("-B");
        command.add("-ntp");
        command.add("-f");
        command.add(buildPomPath.toString());
        command.add("-DoutputFormats=XML");
        command.add("-DtimestampedReports=false");
        command.add("-DreportsDirectory=target/pit-reports");
        command.add("-DfailWhenNoMutations=false");
        command.add("-Dthreads=" + defaultPitThreads());
        command.add("-DskipTests=false");
        command.addAll(additionalArguments);
        if (!skipClean) {
            command.add("clean");
        }
        command.add("org.jacoco:jacoco-maven-plugin:" + jacocoVersion + ":prepare-agent");
        command.add("test");
        command.add("org.jacoco:jacoco-maven-plugin:" + jacocoVersion + ":report");
        if (runAggregateCoverage) {
            command.add("org.jacoco:jacoco-maven-plugin:" + jacocoVersion + ":report-aggregate");
        }
        command.add("org.pitest:pitest-maven:" + pitVersion + ":mutationCoverage");
        return command;
    }

    int defaultPitThreads() {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    String resolveMavenExecutable(Path buildRoot, Path explicitMavenExecutable) {
        if (explicitMavenExecutable != null) {
            return toExecutablePath(buildRoot, explicitMavenExecutable);
        }

        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
        String wrapperName = windows ? "mvnw.cmd" : "mvnw";
        Path wrapperPath = buildRoot.resolve(wrapperName);
        if (Files.isRegularFile(wrapperPath)) {
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
}
