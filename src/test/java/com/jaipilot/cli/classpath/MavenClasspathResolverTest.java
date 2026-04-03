package com.jaipilot.cli.classpath;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenClasspathResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void buildClasspathUsesTestScopeDependencies() throws Exception {
        Path projectRoot = tempDir.resolve("repo");
        Path moduleRoot = projectRoot;
        Files.createDirectories(moduleRoot.resolve(".mvn/wrapper"));
        Files.writeString(moduleRoot.resolve(".mvn/wrapper/maven-wrapper.properties"), "distributionUrl=https://repo.maven.apache.org/");
        Files.writeString(moduleRoot.resolve("pom.xml"), "<project/>");

        Path fakeWrapper = moduleRoot.resolve("mvnw");
        Files.writeString(fakeWrapper, """
                #!/usr/bin/env sh
                set -eu
                LOG_FILE="$PWD/maven-wrapper-args.log"
                echo "$*" >> "$LOG_FILE"

                OUTPUT_FILE=""
                for arg in "$@"; do
                  case "$arg" in
                    -Dmdep.outputFile=*)
                      OUTPUT_FILE="${arg#-Dmdep.outputFile=}"
                      ;;
                  esac
                done
                if [ -n "$OUTPUT_FILE" ]; then
                  mkdir -p "$(dirname "$OUTPUT_FILE")"
                  : > "$OUTPUT_FILE"
                fi
                exit 0
                """);
        assertTrue(fakeWrapper.toFile().setExecutable(true));

        MavenClasspathResolver resolver = new MavenClasspathResolver(
                new ResolvedClasspathCache(tempDir.resolve("cache")),
                new ClasspathFingerprintService(),
                new MavenInvokerClient(),
                tempDir.resolve("work")
        );
        resolver.resolveTestClasspath(projectRoot, moduleRoot, ResolutionOptions.defaults());

        String commandLine = Files.readString(moduleRoot.resolve("maven-wrapper-args.log"));
        assertTrue(commandLine.contains("dependency:build-classpath"));
        assertTrue(commandLine.contains("-Dmdep.includeScope=test"));
    }
}
