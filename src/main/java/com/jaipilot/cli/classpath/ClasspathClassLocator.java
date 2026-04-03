package com.jaipilot.cli.classpath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ClasspathClassLocator implements ClassLocator {

    private static final System.Logger LOGGER = System.getLogger(ClasspathClassLocator.class.getName());

    private final MavenCoordinateExtractor coordinateExtractor;
    private final Map<String, Path> classEntryJarMemo;
    private final Set<String> duplicateWarningMemo;

    public ClasspathClassLocator() {
        this(new MavenCoordinateExtractor(), new ConcurrentHashMap<>(), ConcurrentHashMap.newKeySet());
    }

    ClasspathClassLocator(
            MavenCoordinateExtractor coordinateExtractor,
            Map<String, Path> classEntryJarMemo,
            Set<String> duplicateWarningMemo
    ) {
        this.coordinateExtractor = coordinateExtractor;
        this.classEntryJarMemo = classEntryJarMemo;
        this.duplicateWarningMemo = duplicateWarningMemo;
    }

    @Override
    public ClassResolutionResult locate(String fqcn, ResolvedClasspath classpath) {
        long startedAt = System.nanoTime();
        String normalizedFqcn = ClassNameParser.normalizeFqcn(fqcn);
        String classEntryPath = ClassNameParser.classEntryPath(normalizedFqcn);

        ClassResolutionResult mainResult = locateInOutputDirs(
                normalizedFqcn,
                classEntryPath,
                classpath.mainOutputDirs(),
                classpath.mainSourceRoots(),
                LocationKind.PROJECT_MAIN_CLASS
        );
        if (mainResult.kind() != LocationKind.NOT_FOUND) {
            logDuration(startedAt);
            return mainResult;
        }

        ClassResolutionResult testResult = locateInOutputDirs(
                normalizedFqcn,
                classEntryPath,
                classpath.testOutputDirs(),
                classpath.testSourceRoots(),
                LocationKind.PROJECT_TEST_CLASS
        );
        if (testResult.kind() != LocationKind.NOT_FOUND) {
            logDuration(startedAt);
            return testResult;
        }

        ClassResolutionResult externalResult = locateInExternalJars(normalizedFqcn, classEntryPath, classpath);
        logDuration(startedAt);
        return externalResult;
    }

    private ClassResolutionResult locateInOutputDirs(
            String fqcn,
            String classEntryPath,
            List<Path> outputDirs,
            List<Path> sourceRoots,
            LocationKind locationKind
    ) {
        for (Path outputDir : outputDirs) {
            Path classFile = outputDir.resolve(classEntryPath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(classFile)) {
                continue;
            }
            return new ClassResolutionResult(
                    fqcn,
                    locationKind,
                    outputDir.toAbsolutePath().normalize(),
                    classEntryPath,
                    mapSourcePath(fqcn, sourceRoots),
                    Optional.empty()
            );
        }
        return new ClassResolutionResult(
                fqcn,
                LocationKind.NOT_FOUND,
                null,
                classEntryPath,
                Optional.empty(),
                Optional.empty()
        );
    }

    private ClassResolutionResult locateInExternalJars(String fqcn, String classEntryPath, ResolvedClasspath classpath) {
        String memoKey = classpath.fingerprint() + "::" + classEntryPath;
        Path memoized = classEntryJarMemo.get(memoKey);
        if (memoized != null && containsZipEntry(memoized, classEntryPath)) {
            return new ClassResolutionResult(
                    fqcn,
                    LocationKind.EXTERNAL_JAR,
                    memoized,
                    classEntryPath,
                    Optional.empty(),
                    coordinateExtractor.extract(memoized)
            );
        }

        List<Path> matches = new ArrayList<>();
        for (Path entry : classpath.classpathEntries()) {
            if (!Files.isRegularFile(entry) || !entry.getFileName().toString().endsWith(".jar")) {
                continue;
            }
            if (!containsZipEntry(entry, classEntryPath)) {
                continue;
            }
            matches.add(entry.toAbsolutePath().normalize());
        }

        if (!matches.isEmpty()) {
            Path selectedJar = matches.get(0);
            classEntryJarMemo.put(memoKey, selectedJar);
            logDuplicateMatchesIfAny(memoKey, fqcn, selectedJar, matches.subList(1, matches.size()));
            return new ClassResolutionResult(
                    fqcn,
                    LocationKind.EXTERNAL_JAR,
                    selectedJar,
                    classEntryPath,
                    Optional.empty(),
                    coordinateExtractor.extract(selectedJar)
            );
        }

        return new ClassResolutionResult(
                fqcn,
                LocationKind.NOT_FOUND,
                null,
                classEntryPath,
                Optional.empty(),
                Optional.empty()
        );
    }

    private Optional<Path> mapSourcePath(String fqcn, List<Path> sourceRoots) {
        String sourceEntryPath = ClassNameParser.sourceEntryPath(fqcn);
        Path firstCandidate = null;
        for (Path sourceRoot : sourceRoots) {
            Path candidate = sourceRoot.resolve(sourceEntryPath).toAbsolutePath().normalize();
            if (firstCandidate == null) {
                firstCandidate = candidate;
            }
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.ofNullable(firstCandidate);
    }

    private boolean containsZipEntry(Path jarPath, String classEntryPath) {
        try (ZipFile zipFile = new ZipFile(jarPath.toFile())) {
            ZipEntry entry = zipFile.getEntry(classEntryPath);
            return entry != null && !entry.isDirectory();
        } catch (IOException ignored) {
            return false;
        }
    }

    private void logDuration(long startedAt) {
        long durationMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        LOGGER.log(System.Logger.Level.DEBUG, "class lookup duration: {0}ms", durationMillis);
    }

    private void logDuplicateMatchesIfAny(String memoKey, String fqcn, Path selectedJar, List<Path> alternateJars) {
        if (alternateJars == null || alternateJars.isEmpty()) {
            return;
        }

        String warningKey = memoKey + "::duplicates";
        if (!duplicateWarningMemo.add(warningKey)) {
            return;
        }

        String selected = describeJar(selectedJar);
        String alternates = alternateJars.stream()
                .map(this::describeJar)
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
        LOGGER.log(
                System.Logger.Level.WARNING,
                "Multiple classpath matches found for {0}. Using {1}; ignored {2}",
                fqcn,
                selected,
                alternates
        );
    }

    private String describeJar(Path jarPath) {
        Optional<MavenCoordinates> coordinates = coordinateExtractor.extract(jarPath);
        if (coordinates.isEmpty()) {
            return jarPath.toString();
        }
        MavenCoordinates coordinate = coordinates.get();
        return coordinate.groupId() + ":" + coordinate.artifactId() + ":" + coordinate.version() + " (" + jarPath + ")";
    }
}
