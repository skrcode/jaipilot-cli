package com.jaipilot.cli.classpath;

import java.nio.file.Path;

public final class BuildToolClasspathResolver implements ClasspathResolver {

    private static final System.Logger LOGGER = System.getLogger(BuildToolClasspathResolver.class.getName());

    private final BuildToolDetector buildToolDetector;
    private final CompileCapableClasspathResolver mavenResolver;
    private final CompileCapableClasspathResolver gradleResolver;

    public BuildToolClasspathResolver() {
        this(new BuildToolDetector(), new MavenClasspathResolver(), new GradleClasspathResolver());
    }

    BuildToolClasspathResolver(
            BuildToolDetector buildToolDetector,
            CompileCapableClasspathResolver mavenResolver,
            CompileCapableClasspathResolver gradleResolver
    ) {
        this.buildToolDetector = buildToolDetector;
        this.mavenResolver = mavenResolver;
        this.gradleResolver = gradleResolver;
    }

    @Override
    public ResolvedClasspath resolveTestClasspath(Path projectRoot, Path moduleRoot, ResolutionOptions options) {
        return resolveTestClasspath(projectRoot, moduleRoot, options, false);
    }

    ResolvedClasspath resolveTestClasspath(Path projectRoot, Path moduleRoot, ResolutionOptions options, boolean forceCompile) {
        BuildToolType buildTool = buildToolDetector.detectBuildTool(projectRoot, moduleRoot);
        LOGGER.log(System.Logger.Level.DEBUG, "build tool detected: {0}", buildTool);
        return switch (buildTool) {
            case MAVEN -> mavenResolver.resolveTestClasspath(projectRoot, moduleRoot, options, forceCompile);
            case GRADLE -> gradleResolver.resolveTestClasspath(projectRoot, moduleRoot, options, forceCompile);
        };
    }
}
