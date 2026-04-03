package com.jaipilot.cli.classpath;

public record MavenCoordinates(
        String groupId,
        String artifactId,
        String version
) {
}
