package com.jaipilot.cli.classpath;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.maven.shared.invoker.MavenInvocationException;

public final class DefaultSourceResolver implements SourceResolver {

    private static final System.Logger LOGGER = System.getLogger(DefaultSourceResolver.class.getName());

    private final Path projectRoot;
    private final Path moduleRoot;
    private final MavenInvokerClient mavenInvokerClient;
    private final MavenCoordinateExtractor coordinateExtractor;

    public DefaultSourceResolver(Path projectRoot, Path moduleRoot) {
        this(projectRoot, moduleRoot, new MavenInvokerClient(), new MavenCoordinateExtractor());
    }

    DefaultSourceResolver(
            Path projectRoot,
            Path moduleRoot,
            MavenInvokerClient mavenInvokerClient,
            MavenCoordinateExtractor coordinateExtractor
    ) {
        this.projectRoot = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
        this.moduleRoot = moduleRoot == null ? null : moduleRoot.toAbsolutePath().normalize();
        this.mavenInvokerClient = mavenInvokerClient;
        this.coordinateExtractor = coordinateExtractor;
    }

    @Override
    public Optional<ResolvedSource> resolveSource(ClassResolutionResult classResult, ResolutionOptions options) {
        long startedAt = System.nanoTime();
        ResolutionOptions normalizedOptions = options == null ? ResolutionOptions.defaults() : options;
        try {
            Optional<ResolvedSource> result = resolveSourceInternal(classResult, normalizedOptions);
            long durationMillis = (System.nanoTime() - startedAt) / 1_000_000L;
            LOGGER.log(System.Logger.Level.DEBUG, "source lookup duration: {0}ms", durationMillis);
            return result;
        } catch (ClasspathResolutionException exception) {
            long durationMillis = (System.nanoTime() - startedAt) / 1_000_000L;
            LOGGER.log(System.Logger.Level.DEBUG, "source lookup duration: {0}ms", durationMillis);
            throw exception;
        }
    }

    private Optional<ResolvedSource> resolveSourceInternal(ClassResolutionResult classResult, ResolutionOptions options) {
        if (classResult == null) {
            return Optional.empty();
        }

        if (classResult.mappedSourcePath().isPresent()) {
            Path sourcePath = classResult.mappedSourcePath().get();
            if (Files.isRegularFile(sourcePath)) {
                return Optional.of(new ResolvedSource(
                        classResult.fqcn(),
                        SourceOrigin.WORKSPACE_FILE,
                        sourcePath,
                        readFile(sourcePath)
                ));
            }
        }

        if (classResult.kind() != LocationKind.EXTERNAL_JAR || !options.resolveExternalSources()) {
            return Optional.empty();
        }

        Path jarPath = classResult.containerPath();
        if (jarPath == null) {
            return Optional.empty();
        }

        MavenCoordinates coordinates = classResult.externalCoordinates()
                .or(() -> coordinateExtractor.extract(jarPath))
                .orElse(null);
        if (coordinates == null) {
            return Optional.empty();
        }

        Path sourceJar = locateSourceJar(jarPath, coordinates).orElse(null);
        if (sourceJar == null) {
            fetchSourceJar(coordinates, options);
            sourceJar = locateSourceJar(jarPath, coordinates).orElse(null);
        }
        if (sourceJar == null) {
            return Optional.empty();
        }

        String sourceEntryPath = ClassNameParser.sourceEntryPath(classResult.fqcn());
        Optional<String> sourceText = readZipEntry(sourceJar, sourceEntryPath);
        if (sourceText.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new ResolvedSource(
                classResult.fqcn(),
                SourceOrigin.SOURCES_JAR,
                sourceJar,
                sourceText.get()
        ));
    }

    private String readFile(Path sourcePath) {
        try {
            return Files.readString(sourcePath, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new ClasspathResolutionException(new ResolutionFailure(
                    ResolutionFailureCategory.SOURCE_NOT_AVAILABLE,
                    null,
                    moduleRoot,
                    "read workspace source " + sourcePath,
                    exception.getMessage()
            ), exception);
        }
    }

    private Optional<Path> locateSourceJar(Path dependencyJar, MavenCoordinates coordinates) {
        for (Path candidate : sourceJarCandidates(dependencyJar, coordinates)) {
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate.toAbsolutePath().normalize());
            }
        }
        return Optional.empty();
    }

    private List<Path> sourceJarCandidates(Path dependencyJar, MavenCoordinates coordinates) {
        List<Path> candidates = new ArrayList<>();
        Path normalizedJar = dependencyJar.toAbsolutePath().normalize();

        String fileName = normalizedJar.getFileName() == null ? "" : normalizedJar.getFileName().toString();
        if (fileName.endsWith(".jar")) {
            String baseName = fileName.substring(0, fileName.length() - ".jar".length());
            Path parent = normalizedJar.getParent();
            if (parent != null) {
                candidates.add(parent.resolve(baseName + "-sources.jar"));
                candidates.add(parent.resolve(baseName + "-source.jar"));

                try (var paths = Files.list(parent)) {
                    paths.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName() != null)
                            .filter(path -> path.getFileName().toString().endsWith("-sources.jar"))
                            .forEach(candidates::add);
                } catch (IOException ignored) {
                    // Ignore unreadable directories.
                }
            }
        }

        for (Path repositoryRoot : localMavenRepositories()) {
            Path coordinatePath = repositoryRoot
                    .resolve(coordinates.groupId().replace('.', '/'))
                    .resolve(coordinates.artifactId())
                    .resolve(coordinates.version());
            String prefix = coordinates.artifactId() + '-' + coordinates.version();
            candidates.add(coordinatePath.resolve(prefix + "-sources.jar"));
            candidates.add(coordinatePath.resolve(prefix + "-source.jar"));
        }

        return candidates.stream()
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .distinct()
                .toList();
    }

    private void fetchSourceJar(MavenCoordinates coordinates, ResolutionOptions options) {
        if (projectRoot == null || moduleRoot == null) {
            return;
        }

        Path executable = BuildExecutableResolver.resolveMavenExecutable(projectRoot, moduleRoot);
        List<String> args = new ArrayList<>(options.buildArgs());
        args.add("-Dtransitive=false");
        args.add("-Dartifact=" + coordinates.groupId() + ':' + coordinates.artifactId() + ':' + coordinates.version() + ":jar:sources");
        try {
            MavenInvokerClient.MavenExecutionResult result = mavenInvokerClient.execute(
                    moduleRoot,
                    executable,
                    List.of("dependency:get"),
                    args
            );
            if (!result.isSuccessful()) {
                LOGGER.log(System.Logger.Level.DEBUG,
                        "dependency:get for sources failed for {0}:{1}:{2}",
                        coordinates.groupId(),
                        coordinates.artifactId(),
                        coordinates.version());
            }
        } catch (MavenInvocationException ignored) {
            // Source lookup is optional and lazy; ignore fetch failures.
        }
    }

    private Optional<String> readZipEntry(Path sourceJar, String sourceEntryPath) {
        try (ZipFile zipFile = new ZipFile(sourceJar.toFile())) {
            ZipEntry entry = zipFile.getEntry(sourceEntryPath);
            if (entry == null || entry.isDirectory()) {
                return Optional.empty();
            }
            try (InputStream inputStream = zipFile.getInputStream(entry)) {
                return Optional.of(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private List<Path> localMavenRepositories() {
        String userHome = System.getProperty("user.home", "");
        if (userHome.isBlank()) {
            return List.of();
        }
        Path homePath = Path.of(userHome);

        List<Path> roots = new ArrayList<>();
        String explicitRepo = firstNonBlank(
                System.getProperty("maven.repo.local"),
                System.getenv("MAVEN_REPO_LOCAL"),
                System.getenv("M2_REPO")
        );
        if (explicitRepo != null) {
            roots.add(resolveConfiguredPath(homePath, explicitRepo));
        }
        roots.add(homePath.resolve(".m2/repository"));
        return roots.stream()
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .distinct()
                .toList();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static Path resolveConfiguredPath(Path homePath, String configuredPath) {
        String normalized = configuredPath == null ? "" : configuredPath.trim();
        if (normalized.isBlank()) {
            return homePath.resolve(".m2/repository").normalize();
        }

        String userHome = homePath.toString();
        normalized = normalized.replace("${user.home}", userHome);
        String envHome = System.getenv("HOME");
        if (envHome != null && !envHome.isBlank()) {
            normalized = normalized.replace("${env.HOME}", envHome);
        }
        if (normalized.startsWith("~")) {
            normalized = userHome + normalized.substring(1);
        }

        Path path = Path.of(normalized);
        if (!path.isAbsolute()) {
            path = homePath.resolve(path);
        }
        return path.normalize();
    }
}
