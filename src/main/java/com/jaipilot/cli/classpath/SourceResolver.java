package com.jaipilot.cli.classpath;

import java.util.Optional;

public interface SourceResolver {

    Optional<ResolvedSource> resolveSource(ClassResolutionResult classResult, ResolutionOptions options);
}
