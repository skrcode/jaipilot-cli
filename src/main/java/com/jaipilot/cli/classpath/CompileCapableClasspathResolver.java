package com.jaipilot.cli.classpath;

import java.nio.file.Path;

interface CompileCapableClasspathResolver extends ClasspathResolver {

    ResolvedClasspath resolveTestClasspath(Path projectRoot, Path moduleRoot, ResolutionOptions options, boolean forceCompile);
}
