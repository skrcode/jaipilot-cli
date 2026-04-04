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

public final class DefaultSourceResolver implements SourceResolver {

    private static final System.Logger LOGGER = System.getLogger(DefaultSourceResolver.class.getName());

    private final Path moduleRoot;
    private final CfrDecompiler cfrDecompiler;

    public DefaultSourceResolver(Path projectRoot, Path moduleRoot) {
        this(projectRoot, moduleRoot, new CfrDecompiler());
    }

    DefaultSourceResolver(
            Path projectRoot,
            Path moduleRoot,
            CfrDecompiler cfrDecompiler
    ) {
        this.moduleRoot = moduleRoot == null ? null : moduleRoot.toAbsolutePath().normalize();
        this.cfrDecompiler = cfrDecompiler;
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
        if (classResult == null || classResult.kind() == LocationKind.NOT_FOUND) {
            return Optional.empty();
        }

        Optional<ResolvedSource> workspaceSource = resolveWorkspaceSource(classResult);
        if (workspaceSource.isPresent()) {
            return workspaceSource;
        }

        Optional<ResolvedSource> sourceJarSource = resolveExternalSourceJar(classResult, options);
        if (sourceJarSource.isPresent()) {
            return sourceJarSource;
        }

        return resolveDecompiledSource(classResult, options);
    }

    private Optional<ResolvedSource> resolveWorkspaceSource(ClassResolutionResult classResult) {
        if (classResult.mappedSourcePath().isEmpty()) {
            return Optional.empty();
        }

        Path sourcePath = classResult.mappedSourcePath().get();
        if (!Files.isRegularFile(sourcePath)) {
            return Optional.empty();
        }

        return Optional.of(new ResolvedSource(
                classResult.fqcn(),
                SourceOrigin.WORKSPACE_FILE,
                sourcePath,
                readFile(sourcePath)
        ));
    }

    private Optional<ResolvedSource> resolveExternalSourceJar(ClassResolutionResult classResult, ResolutionOptions options) {
        if (classResult.kind() != LocationKind.EXTERNAL_JAR || !options.resolveExternalSources()) {
            return Optional.empty();
        }

        Path jarPath = classResult.containerPath();
        if (jarPath == null) {
            return Optional.empty();
        }

        Path sourceJar = locateSourceJar(jarPath).orElse(null);
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

    private Optional<ResolvedSource> resolveDecompiledSource(ClassResolutionResult classResult, ResolutionOptions options) {
        if (classResult.kind() == LocationKind.EXTERNAL_JAR && !options.resolveExternalSources()) {
            return Optional.empty();
        }

        return cfrDecompiler.decompile(classResult.containerPath(), classResult.classEntryPath())
                .map(sourceText -> new ResolvedSource(
                        classResult.fqcn(),
                        SourceOrigin.DECOMPILED_CLASS,
                        classResult.containerPath(),
                        sourceText
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

    private Optional<Path> locateSourceJar(Path dependencyJar) {
        for (Path candidate : sourceJarCandidates(dependencyJar)) {
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate.toAbsolutePath().normalize());
            }
        }
        return Optional.empty();
    }

    private List<Path> sourceJarCandidates(Path dependencyJar) {
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

        return candidates.stream()
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .distinct()
                .toList();
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

}
