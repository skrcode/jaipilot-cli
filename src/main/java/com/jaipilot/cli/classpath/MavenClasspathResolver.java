package com.jaipilot.cli.classpath;

import com.jaipilot.cli.util.JaipilotPaths;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.maven.shared.invoker.MavenInvocationException;

public final class MavenClasspathResolver implements CompileCapableClasspathResolver {

    private static final System.Logger LOGGER = System.getLogger(MavenClasspathResolver.class.getName());
    private static final Pattern PATH_SEPARATOR_PATTERN = Pattern.compile(Pattern.quote(File.pathSeparator));

    private final ResolvedClasspathCache classpathCache;
    private final ClasspathFingerprintService fingerprintService;
    private final MavenInvokerClient invokerClient;
    private final Path workingDirectory;

    public MavenClasspathResolver() {
        this(
                new ResolvedClasspathCache(cacheRoot().resolve("resolved")),
                new ClasspathFingerprintService(),
                new MavenInvokerClient(),
                cacheRoot().resolve("work")
        );
    }

    MavenClasspathResolver(
            ResolvedClasspathCache classpathCache,
            ClasspathFingerprintService fingerprintService,
            MavenInvokerClient invokerClient,
            Path workingDirectory
    ) {
        this.classpathCache = classpathCache;
        this.fingerprintService = fingerprintService;
        this.invokerClient = invokerClient;
        this.workingDirectory = workingDirectory.toAbsolutePath().normalize();
    }

    @Override
    public ResolvedClasspath resolveTestClasspath(Path projectRoot, Path moduleRoot, ResolutionOptions options) {
        return resolveTestClasspath(projectRoot, moduleRoot, options, false);
    }

    @Override
    public ResolvedClasspath resolveTestClasspath(
            Path projectRoot,
            Path moduleRoot,
            ResolutionOptions options,
            boolean forceCompile
    ) {
        Path normalizedProjectRoot = normalizeDirectory(projectRoot, "projectRoot");
        Path normalizedModuleRoot = normalizeDirectory(moduleRoot, "moduleRoot");
        ResolutionOptions normalizedOptions = options == null ? ResolutionOptions.defaults() : options;
        Path executable = BuildExecutableResolver.resolveMavenExecutable(normalizedProjectRoot, normalizedModuleRoot);

        LOGGER.log(System.Logger.Level.DEBUG, "build tool detected: Maven for module {0}", normalizedModuleRoot);
        LOGGER.log(System.Logger.Level.DEBUG, "wrapper/global executable chosen: {0}", executable);

        String fingerprint = fingerprintService.fingerprint(
                BuildToolType.MAVEN,
                normalizedProjectRoot,
                normalizedModuleRoot,
                executable,
                normalizedOptions
        );

        if (!forceCompile) {
            ResolvedClasspath cached = classpathCache.read(fingerprint).orElse(null);
            if (cached != null) {
                LOGGER.log(System.Logger.Level.DEBUG, "cache hit for Maven classpath fingerprint {0}", fingerprint);
                return cached;
            }
            LOGGER.log(System.Logger.Level.INFO, "classpath cache miss for Maven module {0}", normalizedModuleRoot);
        }

        long startedAt = System.nanoTime();
        if (forceCompile) {
            LOGGER.log(System.Logger.Level.INFO, "compile fallback triggered for Maven module {0}", normalizedModuleRoot);
            runTestCompile(normalizedModuleRoot, executable, normalizedOptions);
        }

        List<Path> classpathEntries = runBuildClasspath(normalizedModuleRoot, executable, normalizedOptions, fingerprint);
        ResolvedClasspath resolved = buildResolvedClasspath(
                normalizedProjectRoot,
                normalizedModuleRoot,
                classpathEntries,
                fingerprint
        );
        classpathCache.write(resolved);

        long durationMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        LOGGER.log(System.Logger.Level.DEBUG, "classpath resolution duration (Maven): {0}ms", durationMillis);
        return resolved;
    }

    private List<Path> runBuildClasspath(
            Path moduleRoot,
            Path executable,
            ResolutionOptions options,
            String fingerprint
    ) {
        Path outputFile = workingDirectory.resolve(fingerprint + "-maven-classpath.txt");
        try {
            Files.createDirectories(outputFile.getParent());
            Files.deleteIfExists(outputFile);
        } catch (IOException exception) {
            throw new ClasspathResolutionException(new ResolutionFailure(
                    ResolutionFailureCategory.CLASSPATH_RESOLUTION_FAILED,
                    BuildToolType.MAVEN,
                    moduleRoot,
                    "prepare dependency:build-classpath output",
                    exception.getMessage()
            ), exception);
        }

        List<String> args = new ArrayList<>(options.buildArgs());
        args.add("-Dmdep.includeScope=test");
        args.add("-Dmdep.outputFile=" + outputFile);

        MavenInvokerClient.MavenExecutionResult executionResult;
        try {
            executionResult = invokerClient.execute(
                    moduleRoot,
                    executable,
                    List.of("dependency:build-classpath"),
                    args
            );
        } catch (MavenInvocationException exception) {
            throw invocationFailure(
                    moduleRoot,
                    "dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=" + outputFile,
                    exception,
                    ResolutionFailureCategory.CLASSPATH_RESOLUTION_FAILED
            );
        }

        if (!executionResult.isSuccessful()) {
            throw commandFailure(
                    BuildToolType.MAVEN,
                    moduleRoot,
                    "dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=" + outputFile,
                    executionResult,
                    ResolutionFailureCategory.CLASSPATH_RESOLUTION_FAILED
            );
        }

        if (!Files.isRegularFile(outputFile)) {
            throw new ClasspathResolutionException(new ResolutionFailure(
                    ResolutionFailureCategory.CLASSPATH_RESOLUTION_FAILED,
                    BuildToolType.MAVEN,
                    moduleRoot,
                    "dependency:build-classpath",
                    "Classpath output file was not produced: " + outputFile
            ));
        }

        try {
            String classpathText = Files.readString(outputFile).trim();
            if (classpathText.isBlank()) {
                return List.of();
            }
            Set<Path> entries = new LinkedHashSet<>();
            for (String token : PATH_SEPARATOR_PATTERN.split(classpathText)) {
                if (token == null || token.isBlank()) {
                    continue;
                }
                entries.add(Path.of(token).toAbsolutePath().normalize());
            }
            return List.copyOf(entries);
        } catch (IOException exception) {
            throw new ClasspathResolutionException(new ResolutionFailure(
                    ResolutionFailureCategory.CLASSPATH_RESOLUTION_FAILED,
                    BuildToolType.MAVEN,
                    moduleRoot,
                    "read dependency:build-classpath output",
                    exception.getMessage()
            ), exception);
        }
    }

    private void runTestCompile(Path moduleRoot, Path executable, ResolutionOptions options) {
        MavenInvokerClient.MavenExecutionResult executionResult;
        try {
            executionResult = invokerClient.execute(
                    moduleRoot,
                    executable,
                    List.of("test-compile"),
                    options.buildArgs()
            );
        } catch (MavenInvocationException exception) {
            throw invocationFailure(
                    moduleRoot,
                    "test-compile",
                    exception,
                    ResolutionFailureCategory.COMPILE_FALLBACK_FAILED
            );
        }

        if (!executionResult.isSuccessful()) {
            throw commandFailure(
                    BuildToolType.MAVEN,
                    moduleRoot,
                    "test-compile",
                    executionResult,
                    ResolutionFailureCategory.COMPILE_FALLBACK_FAILED
            );
        }
    }

    private ResolvedClasspath buildResolvedClasspath(
            Path projectRoot,
            Path moduleRoot,
            List<Path> classpathEntries,
            String fingerprint
    ) {
        List<Path> mainOutputDirs = new ArrayList<>();
        List<Path> testOutputDirs = new ArrayList<>();

        Path moduleMain = moduleRoot.resolve("target/classes").toAbsolutePath().normalize();
        Path moduleTest = moduleRoot.resolve("target/test-classes").toAbsolutePath().normalize();
        if (Files.isDirectory(moduleMain)) {
            mainOutputDirs.add(moduleMain);
        }
        if (Files.isDirectory(moduleTest)) {
            testOutputDirs.add(moduleTest);
        }

        for (Path entry : classpathEntries) {
            if (!Files.isDirectory(entry)) {
                continue;
            }
            String normalized = entry.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
            if (normalized.endsWith("/test-classes") || normalized.contains("/target/test-classes")) {
                testOutputDirs.add(entry);
                continue;
            }
            if (normalized.endsWith("/classes") || normalized.contains("/target/classes")) {
                mainOutputDirs.add(entry);
            }
        }

        List<Path> mainSources = List.of(moduleRoot.resolve("src/main/java").toAbsolutePath().normalize());
        List<Path> testSources = List.of(moduleRoot.resolve("src/test/java").toAbsolutePath().normalize());

        return new ResolvedClasspath(
                BuildToolType.MAVEN,
                projectRoot,
                moduleRoot,
                classpathEntries,
                mainOutputDirs,
                testOutputDirs,
                mainSources,
                testSources,
                fingerprint
        );
    }

    private ClasspathResolutionException invocationFailure(
            Path moduleRoot,
            String action,
            Exception exception,
            ResolutionFailureCategory defaultCategory
    ) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        ResolutionFailureCategory category = message.contains("Cannot run program")
                ? ResolutionFailureCategory.BUILD_TOOL_NOT_FOUND
                : defaultCategory;
        return new ClasspathResolutionException(new ResolutionFailure(
                category,
                BuildToolType.MAVEN,
                moduleRoot,
                action,
                snippet(message)
        ), exception);
    }

    private ClasspathResolutionException commandFailure(
            BuildToolType buildTool,
            Path moduleRoot,
            String action,
            MavenInvokerClient.MavenExecutionResult result,
            ResolutionFailureCategory category
    ) {
        StringBuilder output = new StringBuilder();
        if (result.executionException() != null && result.executionException().cause() != null
                && result.executionException().cause().getMessage() != null) {
            output.append(result.executionException().cause().getMessage()).append('\n');
        }
        if (!result.stderr().isEmpty()) {
            output.append(String.join("\n", result.stderr()));
        } else if (!result.stdout().isEmpty()) {
            output.append(String.join("\n", result.stdout()));
        }

        ResolutionFailureCategory resolvedCategory = output.toString().contains("Cannot run program")
                ? ResolutionFailureCategory.BUILD_TOOL_NOT_FOUND
                : category;

        return new ClasspathResolutionException(new ResolutionFailure(
                resolvedCategory,
                buildTool,
                moduleRoot,
                action,
                snippet(output.toString())
        ));
    }

    private static String snippet(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replace('\r', '\n').replace("\n\n", "\n").trim();
        return normalized.length() <= 1500 ? normalized : normalized.substring(0, 1500);
    }

    private static Path normalizeDirectory(Path path, String label) {
        if (path == null) {
            throw new IllegalArgumentException(label + " must not be null");
        }
        return path.toAbsolutePath().normalize();
    }

    private static Path cacheRoot() {
        return JaipilotPaths.resolveConfigHome()
                .resolve("classpath-cache")
                .resolve("v1")
                .resolve("maven");
    }
}
