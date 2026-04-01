package com.jaipilot.cli.process;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

public enum BuildTool {
    MAVEN("Maven"),
    GRADLE("Gradle");

    private final String displayName;

    BuildTool(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static Optional<BuildTool> fromExecutable(Path executable) {
        if (executable == null) {
            return Optional.empty();
        }
        String fileName = executable.getFileName() == null
                ? executable.toString()
                : executable.getFileName().toString();
        String normalized = fileName.toLowerCase(Locale.ROOT);
        if (normalized.equals("mvn")
                || normalized.equals("mvn.cmd")
                || normalized.equals("mvnw")
                || normalized.equals("mvnw.cmd")) {
            return Optional.of(MAVEN);
        }
        if (normalized.equals("gradle")
                || normalized.equals("gradle.bat")
                || normalized.equals("gradlew")
                || normalized.equals("gradlew.bat")) {
            return Optional.of(GRADLE);
        }
        return Optional.empty();
    }
}
