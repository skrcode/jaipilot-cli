package com.jaipilot.cli.classpath;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class BuildToolClassResolutionService {

    private static final System.Logger LOGGER = System.getLogger(BuildToolClassResolutionService.class.getName());

    private final BuildToolClasspathResolver classpathResolver;
    private final ClassLocator classLocator;
    private final Set<String> compileFallbackAttemptedFingerprints;

    public BuildToolClassResolutionService() {
        this(new BuildToolClasspathResolver(), new ClasspathClassLocator(), ConcurrentHashMap.newKeySet());
    }

    BuildToolClassResolutionService(
            BuildToolClasspathResolver classpathResolver,
            ClassLocator classLocator,
            Set<String> compileFallbackAttemptedFingerprints
    ) {
        this.classpathResolver = classpathResolver;
        this.classLocator = classLocator;
        this.compileFallbackAttemptedFingerprints = compileFallbackAttemptedFingerprints;
    }

    public ResolvedClasspath resolveClasspath(Path projectRoot, Path moduleRoot, ResolutionOptions options) {
        return classpathResolver.resolveTestClasspath(projectRoot, moduleRoot, options);
    }

    public ClassResolutionResult locate(
            String fqcnOrImport,
            Path projectRoot,
            Path moduleRoot,
            ResolutionOptions options
    ) {
        ResolutionOptions normalizedOptions = options == null ? ResolutionOptions.defaults() : options;
        String normalizedFqcn = ClassNameParser.normalizeFqcn(fqcnOrImport);

        ResolvedClasspath classpath = classpathResolver.resolveTestClasspath(projectRoot, moduleRoot, normalizedOptions);
        ClassResolutionResult result = classLocator.locate(normalizedFqcn, classpath);
        if (result.kind() != LocationKind.NOT_FOUND) {
            return result;
        }

        if (!normalizedOptions.allowCompileFallback()) {
            LOGGER.log(System.Logger.Level.DEBUG, "compile fallback skipped for {0}", classpath.moduleRoot());
            return result;
        }

        if (!compileFallbackAttemptedFingerprints.add(classpath.fingerprint())) {
            LOGGER.log(System.Logger.Level.DEBUG,
                    "compile fallback skipped because it already ran for fingerprint {0}",
                    classpath.fingerprint());
            return result;
        }

        LOGGER.log(System.Logger.Level.INFO, "compile fallback triggered after class miss for module {0}", classpath.moduleRoot());
        ResolvedClasspath fallbackClasspath = classpathResolver.resolveTestClasspath(
                projectRoot,
                moduleRoot,
                normalizedOptions,
                true
        );
        return classLocator.locate(normalizedFqcn, fallbackClasspath);
    }

    public ClassResolutionResult locateOrThrow(
            String fqcnOrImport,
            Path projectRoot,
            Path moduleRoot,
            ResolutionOptions options
    ) {
        ClassResolutionResult result = locate(fqcnOrImport, projectRoot, moduleRoot, options);
        if (result.kind() != LocationKind.NOT_FOUND) {
            return result;
        }
        throw new ClasspathResolutionException(new ResolutionFailure(
                ResolutionFailureCategory.CLASS_NOT_FOUND_ON_RESOLVED_CLASSPATH,
                null,
                moduleRoot,
                "locate class " + fqcnOrImport,
                "Class was not found on resolved classpath."
        ));
    }

    public Optional<ResolvedSource> resolveSource(
            ClassResolutionResult classResult,
            Path projectRoot,
            Path moduleRoot,
            ResolutionOptions options
    ) {
        return new DefaultSourceResolver(projectRoot, moduleRoot).resolveSource(classResult, options);
    }

    public ResolvedSource resolveSourceOrThrow(
            ClassResolutionResult classResult,
            Path projectRoot,
            Path moduleRoot,
            ResolutionOptions options
    ) {
        return resolveSource(classResult, projectRoot, moduleRoot, options)
                .orElseThrow(() -> new ClasspathResolutionException(new ResolutionFailure(
                        ResolutionFailureCategory.SOURCE_NOT_AVAILABLE,
                        null,
                        moduleRoot,
                        "resolve source for " + (classResult == null ? "<null>" : classResult.fqcn()),
                        "Source was not available."
                )));
    }
}
