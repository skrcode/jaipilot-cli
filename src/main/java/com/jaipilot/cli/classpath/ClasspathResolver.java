package com.jaipilot.cli.classpath;

import java.nio.file.Path;

public interface ClasspathResolver {

    ResolvedClasspath resolveTestClasspath(Path projectRoot, Path moduleRoot, ResolutionOptions options);
}
