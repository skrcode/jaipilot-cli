package com.jaipilot.cli.classpath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

final class BuildExecutableResolver {

    private BuildExecutableResolver() {
    }

    static Path resolveMavenExecutable(Path projectRoot, Path moduleRoot) {
        boolean windows = isWindows();
        String wrapperName = windows ? "mvnw.cmd" : "mvnw";
        Path wrapper = findWrapper(moduleRoot, projectRoot, wrapperName, true);
        if (wrapper != null) {
            return wrapper;
        }
        return Path.of(windows ? "mvn.cmd" : "mvn");
    }

    static Path resolveGradleExecutable(Path projectRoot, Path moduleRoot) {
        boolean windows = isWindows();
        String wrapperName = windows ? "gradlew.bat" : "gradlew";
        Path wrapper = findWrapper(moduleRoot, projectRoot, wrapperName, false);
        if (wrapper != null) {
            return wrapper;
        }
        return Path.of(windows ? "gradle.bat" : "gradle");
    }

    private static Path findWrapper(Path moduleRoot, Path projectRoot, String wrapperName, boolean requireMavenWrapperProps) {
        Path normalizedProjectRoot = projectRoot.toAbsolutePath().normalize();
        Path current = moduleRoot.toAbsolutePath().normalize();
        while (current != null && current.startsWith(normalizedProjectRoot)) {
            Path wrapper = current.resolve(wrapperName);
            if (Files.isRegularFile(wrapper)) {
                if (!requireMavenWrapperProps || Files.isRegularFile(current.resolve(".mvn/wrapper/maven-wrapper.properties"))) {
                    return wrapper.toAbsolutePath().normalize();
                }
            }
            if (current.equals(normalizedProjectRoot)) {
                break;
            }
            current = current.getParent();
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }
}
