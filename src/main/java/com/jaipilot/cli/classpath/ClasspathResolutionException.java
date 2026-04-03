package com.jaipilot.cli.classpath;

public final class ClasspathResolutionException extends IllegalStateException {

    private final ResolutionFailure failure;

    public ClasspathResolutionException(ResolutionFailure failure) {
        this(failure, null);
    }

    public ClasspathResolutionException(ResolutionFailure failure, Throwable cause) {
        super(buildMessage(failure), cause);
        this.failure = failure;
    }

    public ResolutionFailure failure() {
        return failure;
    }

    private static String buildMessage(ResolutionFailure failure) {
        if (failure == null) {
            return "Classpath resolution failed.";
        }
        return "%s [tool=%s, moduleRoot=%s, action=%s, output=%s]"
                .formatted(
                        failure.category(),
                        failure.buildTool(),
                        failure.moduleRoot(),
                        failure.actionSummary(),
                        failure.outputSnippet()
                );
    }
}
