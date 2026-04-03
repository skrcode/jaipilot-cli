package com.jaipilot.cli.classpath;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaipilot.cli.util.JaipilotPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class GradleClasspathResolver implements CompileCapableClasspathResolver {

    private static final System.Logger LOGGER = System.getLogger(GradleClasspathResolver.class.getName());
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String PROBE_OUTPUT_PREFIX = "JAIPILOT_TEST_CLASSPATH_JSON=";
    private static final String GRADLE_PROBE_TASK = "jaipilotPrintTestClasspath";
    private static final String GRADLE_PROBE_INIT_SCRIPT = """
            import groovy.json.JsonOutput
            import org.gradle.api.tasks.SourceSetContainer

            gradle.rootProject {
                tasks.register("jaipilotPrintTestClasspath") {
                    doLast {
                        File moduleDir = new File(System.getProperty("jaipilot.moduleDir", "")).canonicalFile
                        def targetProject = rootProject.allprojects.find { project ->
                            project.projectDir.canonicalFile == moduleDir
                        }
                        if (targetProject == null) {
                            throw new GradleException("Unable to locate Gradle project for module dir: " + moduleDir)
                        }

                        SourceSetContainer sourceSets = targetProject.extensions.findByType(SourceSetContainer)
                        if (sourceSets == null) {
                            throw new GradleException("Java SourceSetContainer unavailable for project " + targetProject.path)
                        }

                        def mainSourceSet = sourceSets.findByName("main")
                        def testSourceSet = sourceSets.findByName("test")

                        def classpathEntries = new LinkedHashSet<String>()
                        def mainOutputDirs = new LinkedHashSet<String>()
                        def testOutputDirs = new LinkedHashSet<String>()
                        def mainSourceRoots = new LinkedHashSet<String>()
                        def testSourceRoots = new LinkedHashSet<String>()

                        if (testSourceSet != null && testSourceSet.runtimeClasspath != null) {
                            testSourceSet.runtimeClasspath.files.each { file ->
                                if (file != null) {
                                    classpathEntries.add(file.absolutePath)
                                }
                            }
                        } else if (mainSourceSet != null && mainSourceSet.runtimeClasspath != null) {
                            mainSourceSet.runtimeClasspath.files.each { file ->
                                if (file != null) {
                                    classpathEntries.add(file.absolutePath)
                                }
                            }
                        }

                        if (mainSourceSet != null) {
                            mainSourceSet.output.classesDirs.files.each { file ->
                                if (file != null) {
                                    mainOutputDirs.add(file.absolutePath)
                                }
                            }
                            if (mainSourceSet.output.resourcesDir != null) {
                                mainOutputDirs.add(mainSourceSet.output.resourcesDir.absolutePath)
                            }
                            mainSourceSet.allJava.srcDirs.each { dir ->
                                if (dir != null) {
                                    mainSourceRoots.add(dir.absolutePath)
                                }
                            }
                        }

                        if (testSourceSet != null) {
                            testSourceSet.output.classesDirs.files.each { file ->
                                if (file != null) {
                                    testOutputDirs.add(file.absolutePath)
                                }
                            }
                            if (testSourceSet.output.resourcesDir != null) {
                                testOutputDirs.add(testSourceSet.output.resourcesDir.absolutePath)
                            }
                            testSourceSet.allJava.srcDirs.each { dir ->
                                if (dir != null) {
                                    testSourceRoots.add(dir.absolutePath)
                                }
                            }
                        }

                        def payload = [
                                projectPath: targetProject.path,
                                classpathEntries: classpathEntries as List,
                                mainOutputDirs: mainOutputDirs as List,
                                testOutputDirs: testOutputDirs as List,
                                mainSourceRoots: mainSourceRoots as List,
                                testSourceRoots: testSourceRoots as List
                        ]
                        println("JAIPILOT_TEST_CLASSPATH_JSON=" + JsonOutput.toJson(payload))
                    }
                }
            }
            """;

    private final ResolvedClasspathCache classpathCache;
    private final ClasspathFingerprintService fingerprintService;
    private final GradleToolingClient toolingClient;
    private final Path workingDirectory;

    public GradleClasspathResolver() {
        this(
                new ResolvedClasspathCache(cacheRoot().resolve("resolved")),
                new ClasspathFingerprintService(),
                new GradleToolingClient(),
                cacheRoot().resolve("work")
        );
    }

    GradleClasspathResolver(
            ResolvedClasspathCache classpathCache,
            ClasspathFingerprintService fingerprintService,
            GradleToolingClient toolingClient,
            Path workingDirectory
    ) {
        this.classpathCache = classpathCache;
        this.fingerprintService = fingerprintService;
        this.toolingClient = toolingClient;
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
        Path executable = BuildExecutableResolver.resolveGradleExecutable(normalizedProjectRoot, normalizedModuleRoot);

        LOGGER.log(System.Logger.Level.DEBUG, "build tool detected: Gradle for module {0}", normalizedModuleRoot);
        LOGGER.log(System.Logger.Level.DEBUG, "wrapper/global executable chosen: {0}", executable);

        String fingerprint = fingerprintService.fingerprint(
                BuildToolType.GRADLE,
                normalizedProjectRoot,
                normalizedModuleRoot,
                executable,
                normalizedOptions
        );

        if (!forceCompile) {
            ResolvedClasspath cached = classpathCache.read(fingerprint).orElse(null);
            if (cached != null) {
                LOGGER.log(System.Logger.Level.DEBUG, "cache hit for Gradle classpath fingerprint {0}", fingerprint);
                return cached;
            }
            LOGGER.log(System.Logger.Level.INFO, "classpath cache miss for Gradle module {0}", normalizedModuleRoot);
        }

        long startedAt = System.nanoTime();
        GradleProbePayload payload;
        if (forceCompile) {
            LOGGER.log(System.Logger.Level.INFO, "compile fallback triggered for Gradle module {0}", normalizedModuleRoot);
            GradleProbePayload initialPayload = runProbe(
                    normalizedProjectRoot,
                    normalizedModuleRoot,
                    normalizedOptions,
                    ResolutionFailureCategory.CLASSPATH_RESOLUTION_FAILED
            );
            runCompileFallback(normalizedProjectRoot, normalizedModuleRoot, normalizedOptions, initialPayload.projectPath());
            payload = runProbe(
                    normalizedProjectRoot,
                    normalizedModuleRoot,
                    normalizedOptions,
                    ResolutionFailureCategory.COMPILE_FALLBACK_FAILED
            );
        } else {
            payload = runProbe(
                    normalizedProjectRoot,
                    normalizedModuleRoot,
                    normalizedOptions,
                    ResolutionFailureCategory.CLASSPATH_RESOLUTION_FAILED
            );
        }

        ResolvedClasspath resolved = buildResolvedClasspath(normalizedProjectRoot, normalizedModuleRoot, payload, fingerprint);
        classpathCache.write(resolved);

        long durationMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        LOGGER.log(System.Logger.Level.DEBUG, "classpath resolution duration (Gradle): {0}ms", durationMillis);
        return resolved;
    }

    private GradleProbePayload runProbe(
            Path projectRoot,
            Path moduleRoot,
            ResolutionOptions options,
            ResolutionFailureCategory failureCategory
    ) {
        Path initScript = ensureProbeInitScript();
        List<String> args = new ArrayList<>(options.buildArgs());
        args.add("--init-script");
        args.add(initScript.toString());
        args.add("-Djaipilot.moduleDir=" + moduleRoot);
        args.add("--console=plain");

        GradleToolingClient.GradleExecutionResult result = toolingClient.run(
                projectRoot,
                List.of(GRADLE_PROBE_TASK),
                args
        );
        if (!result.success()) {
            throw toolingFailure(
                    projectRoot,
                    moduleRoot,
                    "tooling-api task " + GRADLE_PROBE_TASK,
                    result,
                    failureCategory
            );
        }
        return parseProbePayload(projectRoot, moduleRoot, result.stdout(), result.stderr(), failureCategory);
    }

    private void runCompileFallback(Path projectRoot, Path moduleRoot, ResolutionOptions options, String projectPath) {
        String compileTask = qualifyTask(projectPath, "testClasses");
        List<String> args = new ArrayList<>(options.buildArgs());
        args.add("--console=plain");

        GradleToolingClient.GradleExecutionResult result = toolingClient.run(
                projectRoot,
                List.of(compileTask),
                args
        );
        if (!result.success()) {
            throw toolingFailure(
                    projectRoot,
                    moduleRoot,
                    "tooling-api task " + compileTask,
                    result,
                    ResolutionFailureCategory.COMPILE_FALLBACK_FAILED
            );
        }
    }

    private ResolvedClasspath buildResolvedClasspath(
            Path projectRoot,
            Path moduleRoot,
            GradleProbePayload payload,
            String fingerprint
    ) {
        List<Path> classpathEntries = toPaths(payload.classpathEntries());
        List<Path> mainOutputDirs = toPaths(payload.mainOutputDirs());
        List<Path> testOutputDirs = toPaths(payload.testOutputDirs());
        List<Path> mainSourceRoots = toPaths(payload.mainSourceRoots());
        List<Path> testSourceRoots = toPaths(payload.testSourceRoots());

        Set<Path> allMain = new LinkedHashSet<>(mainOutputDirs);
        Set<Path> allTest = new LinkedHashSet<>(testOutputDirs);
        for (Path entry : classpathEntries) {
            if (!Files.isDirectory(entry)) {
                continue;
            }
            String normalized = entry.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
            if (normalized.endsWith("/test-classes") || normalized.contains("/build/classes/java/test")) {
                allTest.add(entry);
                continue;
            }
            if (normalized.endsWith("/classes") || normalized.contains("/build/classes/java/main")) {
                allMain.add(entry);
            }
        }

        if (mainSourceRoots.isEmpty()) {
            mainSourceRoots = List.of(moduleRoot.resolve("src/main/java").toAbsolutePath().normalize());
        }
        if (testSourceRoots.isEmpty()) {
            testSourceRoots = List.of(moduleRoot.resolve("src/test/java").toAbsolutePath().normalize());
        }

        return new ResolvedClasspath(
                BuildToolType.GRADLE,
                projectRoot,
                moduleRoot,
                classpathEntries,
                List.copyOf(allMain),
                List.copyOf(allTest),
                mainSourceRoots,
                testSourceRoots,
                fingerprint
        );
    }

    private GradleProbePayload parseProbePayload(
            Path projectRoot,
            Path moduleRoot,
            String stdout,
            String stderr,
            ResolutionFailureCategory failureCategory
    ) {
        String line = stdout == null ? "" : stdout.lines()
                .filter(current -> current.startsWith(PROBE_OUTPUT_PREFIX))
                .reduce((first, second) -> second)
                .orElse("");
        if (line.isBlank()) {
            throw new ClasspathResolutionException(new ResolutionFailure(
                    failureCategory,
                    BuildToolType.GRADLE,
                    moduleRoot,
                    "parse tooling-api output",
                    snippet(firstNonBlank(stderr, stdout))
            ));
        }

        String json = line.substring(PROBE_OUTPUT_PREFIX.length()).trim();
        try {
            return OBJECT_MAPPER.readValue(json, GradleProbePayload.class);
        } catch (IOException exception) {
            throw new ClasspathResolutionException(new ResolutionFailure(
                    failureCategory,
                    BuildToolType.GRADLE,
                    moduleRoot,
                    "parse tooling-api output json",
                    snippet(json)
            ), exception);
        }
    }

    private Path ensureProbeInitScript() {
        Path scriptPath = workingDirectory.resolve("jaipilot-gradle-classpath-probe.init.gradle");
        try {
            Files.createDirectories(scriptPath.getParent());
            if (!Files.isRegularFile(scriptPath) || !Files.readString(scriptPath).equals(GRADLE_PROBE_INIT_SCRIPT)) {
                Files.writeString(scriptPath, GRADLE_PROBE_INIT_SCRIPT);
            }
            return scriptPath;
        } catch (IOException exception) {
            throw new ClasspathResolutionException(new ResolutionFailure(
                    ResolutionFailureCategory.CLASSPATH_RESOLUTION_FAILED,
                    BuildToolType.GRADLE,
                    workingDirectory,
                    "create gradle init script",
                    exception.getMessage()
            ), exception);
        }
    }

    private ClasspathResolutionException toolingFailure(
            Path projectRoot,
            Path moduleRoot,
            String action,
            GradleToolingClient.GradleExecutionResult result,
            ResolutionFailureCategory defaultCategory
    ) {
        String output = firstNonBlank(result.stderr(), result.stdout(), result.exception() == null
                ? ""
                : result.exception().getMessage());
        ResolutionFailureCategory category = isBuildToolNotFound(output)
                ? ResolutionFailureCategory.BUILD_TOOL_NOT_FOUND
                : defaultCategory;
        return new ClasspathResolutionException(new ResolutionFailure(
                category,
                BuildToolType.GRADLE,
                moduleRoot,
                action + " @ " + projectRoot,
                snippet(output)
        ), result.exception());
    }

    private boolean isBuildToolNotFound(String output) {
        if (output == null || output.isBlank()) {
            return false;
        }
        String normalized = output.toLowerCase(Locale.ROOT);
        return normalized.contains("no such file") && normalized.contains("gradle")
                || normalized.contains("gradle executable")
                || normalized.contains("unable to start the daemon process");
    }

    private static String qualifyTask(String projectPath, String taskName) {
        if (projectPath == null || projectPath.isBlank() || ":".equals(projectPath)) {
            return taskName;
        }
        return projectPath + ':' + taskName;
    }

    private static List<Path> toPaths(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(Path::of)
                .map(path -> path.toAbsolutePath().normalize())
                .distinct()
                .toList();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
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
                .resolve("gradle");
    }

    private record GradleProbePayload(
            String projectPath,
            List<String> classpathEntries,
            List<String> mainOutputDirs,
            List<String> testOutputDirs,
            List<String> mainSourceRoots,
            List<String> testSourceRoots
    ) {

        GradleProbePayload {
            classpathEntries = classpathEntries == null ? List.of() : classpathEntries;
            mainOutputDirs = mainOutputDirs == null ? List.of() : mainOutputDirs;
            testOutputDirs = testOutputDirs == null ? List.of() : testOutputDirs;
            mainSourceRoots = mainSourceRoots == null ? List.of() : mainSourceRoots;
            testSourceRoots = testSourceRoots == null ? List.of() : testSourceRoots;
        }
    }
}
