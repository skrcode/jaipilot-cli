package com.jaipilot.cli.classpath;

public interface ClassLocator {

    ClassResolutionResult locate(String fqcn, ResolvedClasspath classpath);
}
