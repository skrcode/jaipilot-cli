package com.jaipilot.cli.classpath;

import java.util.List;

public record ResolutionOptions(
        List<String> buildArgs,
        boolean allowCompileFallback,
        boolean resolveExternalSources
) {

    public ResolutionOptions {
        buildArgs = buildArgs == null ? List.of() : List.copyOf(buildArgs);
    }

    public static ResolutionOptions defaults() {
        return new ResolutionOptions(List.of(), true, false);
    }
}
