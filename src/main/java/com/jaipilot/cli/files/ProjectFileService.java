package com.jaipilot.cli.files;

import com.jaipilot.cli.classpath.BuildToolClassResolutionService;
import com.jaipilot.cli.classpath.ClasspathResolutionException;
import com.jaipilot.cli.classpath.ResolutionOptions;
import com.jaipilot.cli.classpath.ResolutionFailure;
import com.jaipilot.cli.classpath.ResolvedSource;
import com.jaipilot.cli.process.BuildTool;
import com.jaipilot.cli.util.JavaSourceFormatter;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ProjectFileService {

    private static final System.Logger LOGGER = System.getLogger(ProjectFileService.class.getName());
    private static final String MISSING_CONTEXT_SOURCE_PLACEHOLDER = "Class not found";

    private static final List<Path> JAVA_SOURCE_ROOTS = List.of(
            Path.of("src", "main", "java"),
            Path.of("src", "test", "java")
    );
    private static final List<String> GRADLE_BUILD_FILES = List.of(
            "settings.gradle",
            "settings.gradle.kts",
            "build.gradle",
            "build.gradle.kts"
    );
    private static final Set<String> SKIPPED_SEARCH_DIRECTORY_NAMES = Set.of(
            ".git",
            ".gradle",
            ".idea",
            ".vscode",
            "target",
            "build",
            "out",
            "node_modules"
    );

    private final ContextSourceResolver contextSourceResolver;
    private final Map<String, String> dependencySourceContentCache = new HashMap<>();
    private final Set<String> missingDependencySourcePaths = new HashSet<>();

    public ProjectFileService() {
        this(List.of(), defaultContextSourceResolver());
    }

    ProjectFileService(List<Path> dependencySourceSearchRoots) {
        this(dependencySourceSearchRoots, defaultContextSourceResolver());
    }

    ProjectFileService(List<Path> dependencySourceSearchRoots, ContextSourceResolver contextSourceResolver) {
        // Dependency lookup now resolves through classpath ownership (class -> jar -> source/decompile),
        // so legacy direct source-jar root scanning is intentionally unused.
        this.contextSourceResolver = contextSourceResolver == null
                ? defaultContextSourceResolver()
                : contextSourceResolver;
    }

    public Path resolvePath(Path projectRoot, Path path) {
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return projectRoot.resolve(path).normalize();
    }

    public String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read file " + path, exception);
        }
    }

    public void writeFile(Path path, String content) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String normalizedContent = path.getFileName() != null && path.getFileName().toString().endsWith(".java")
                    ? JavaSourceFormatter.format(content)
                    : content;
            Files.writeString(path, normalizedContent, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write file " + path, exception);
        }
    }

    public Path deriveGeneratedTestPath(Path projectRoot, Path cutPath) {
        Path normalizedCutPath = cutPath.normalize();
        Path projectRelative = projectRoot.relativize(normalizedCutPath);
        Path rewritten = rewriteSourceRoot(projectRelative);
        if (rewritten != null) {
            return projectRoot.resolve(rewritten).normalize();
        }

        String packageName = extractPackageName(readFile(normalizedCutPath));
        Path baseDirectory = projectRoot.resolve("src/test/java");
        Path packagePath = packageName.isBlank() ? Path.of("") : Path.of(packageName.replace('.', '/'));
        return baseDirectory
                .resolve(packagePath)
                .resolve(stripJavaExtension(normalizedCutPath.getFileName().toString()) + "Test.java")
                .normalize();
    }

    public Path findNearestMavenProjectRoot(Path path) {
        Path current = path.normalize();
        if (Files.isRegularFile(current)) {
            current = current.getParent();
        }
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    public Path findNearestBuildProjectRoot(Path path) {
        Path current = path.normalize();
        if (Files.isRegularFile(current)) {
            current = current.getParent();
        }
        while (current != null) {
            if (containsBuildFile(current)) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    public Optional<BuildTool> detectBuildTool(Path projectRoot, Path explicitBuildExecutable) {
        Optional<BuildTool> explicitTool = BuildTool.fromExecutable(explicitBuildExecutable);
        if (explicitTool.isPresent()) {
            return explicitTool;
        }

        if (projectRoot == null) {
            return Optional.empty();
        }
        if (Files.isRegularFile(projectRoot.resolve("pom.xml"))) {
            return Optional.of(BuildTool.MAVEN);
        }
        if (containsGradleBuildFile(projectRoot)) {
            return Optional.of(BuildTool.GRADLE);
        }
        return Optional.empty();
    }

    public String deriveTestSelector(Path testPath) {
        String content = readFile(testPath.normalize());
        String className = stripJavaExtension(testPath.getFileName().toString());
        String packageName = extractPackageName(content);
        if (packageName.isBlank()) {
            return className;
        }
        return packageName + "." + className;
    }

    public String deriveGradleProjectPath(Path projectRoot, Path sourcePath) {
        if (projectRoot == null || sourcePath == null) {
            return "";
        }

        Path normalizedProjectRoot = projectRoot.normalize();
        Path normalizedSourcePath = sourcePath.normalize();
        if (!normalizedSourcePath.startsWith(normalizedProjectRoot)) {
            return "";
        }

        Path projectRelative = normalizedProjectRoot.relativize(normalizedSourcePath);
        int sourceRootIndex = sourceRootIndex(projectRelative);
        if (sourceRootIndex <= 0) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < sourceRootIndex; index++) {
            builder.append(':').append(projectRelative.getName(index));
        }
        return builder.toString();
    }

    public Path inferCutPathFromTestPath(Path projectRoot, Path testPath) {
        Path normalizedTestPath = testPath.normalize();
        Path normalizedProjectRoot = projectRoot.normalize();
        if (normalizedTestPath.startsWith(normalizedProjectRoot)) {
            Path relativeTestPath = normalizedProjectRoot.relativize(normalizedTestPath);
            Path rewritten = rewriteTestRoot(relativeTestPath);
            if (rewritten != null) {
                Path candidate = normalizedProjectRoot.resolve(rewritten).normalize();
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }

        String testFileName = normalizedTestPath.getFileName() == null
                ? ""
                : normalizedTestPath.getFileName().toString();
        for (String suffix : List.of("Test.java", "Tests.java", "IT.java", "ITCase.java")) {
            if (!testFileName.endsWith(suffix)) {
                continue;
            }
            String candidateName = testFileName.substring(0, testFileName.length() - suffix.length()) + ".java";
            try (var paths = Files.walk(normalizedProjectRoot)) {
                Optional<Path> candidate = paths
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName() != null && path.getFileName().toString().equals(candidateName))
                        .filter(path -> normalizeSeparators(path).contains("/src/main/java/"))
                        .findFirst();
                if (candidate.isPresent()) {
                    return candidate.get().normalize();
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to infer class under test for " + testPath, exception);
            }
        }
        return null;
    }

    public List<String> readRequestedContextSources(Path projectRoot, List<String> requestedPaths) {
        return readRequestedContextSources(projectRoot, null, requestedPaths);
    }

    public List<String> readRequestedContextSources(Path projectRoot, Path preferredSourcePath, List<String> requestedPaths) {
        if (requestedPaths == null || requestedPaths.isEmpty()) {
            return List.of();
        }
        return requestedPaths.stream()
                .map(path -> readRequestedContextSourceOrPlaceholder(projectRoot, preferredSourcePath, path))
                .toList();
    }

    private String readRequestedContextSourceOrPlaceholder(Path projectRoot, Path preferredSourcePath, String requestedPath) {
        try {
            return readRequestedContextSource(projectRoot, preferredSourcePath, requestedPath);
        } catch (IllegalStateException exception) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Context source unavailable for {0}; using placeholder. Reason: {1}",
                    requestedPath,
                    exception.getMessage()
            );
            return MISSING_CONTEXT_SOURCE_PLACEHOLDER;
        }
    }

    public List<String> readCachedContextEntries(Path projectRoot, List<String> contextPaths) {
        return readCachedContextEntries(projectRoot, null, contextPaths);
    }

    public List<String> readCachedContextEntries(Path projectRoot, Path preferredSourcePath, List<String> contextPaths) {
        if (contextPaths == null || contextPaths.isEmpty()) {
            return List.of();
        }

        return contextPaths.stream()
                .map(path -> path + " =\n" + readRequestedContextSourceOrPlaceholder(projectRoot, preferredSourcePath, path))
                .toList();
    }

    public List<String> resolveImportedContextClassPaths(Path projectRoot, Path sourcePath) {
        Set<String> resolvedPaths = new LinkedHashSet<>();
        for (String importTarget : extractImportTargets(readFile(sourcePath.normalize()))) {
            if (importTarget.endsWith(".*")) {
                resolvedPaths.addAll(resolveStarImportPaths(
                        projectRoot,
                        sourcePath,
                        importTarget.substring(0, importTarget.length() - 2)
                ));
                continue;
            }
            resolveImportedContextClassPath(projectRoot, sourcePath, importTarget).ifPresent(resolvedPaths::add);
        }
        return List.copyOf(resolvedPaths);
    }

    public void refreshDependencySourceIndex() {
        dependencySourceContentCache.clear();
        missingDependencySourcePaths.clear();
    }

    public String stripJavaExtension(String fileName) {
        if (fileName.endsWith(".java")) {
            return fileName.substring(0, fileName.length() - ".java".length());
        }
        return fileName;
    }

    private String readRequestedContextSource(Path projectRoot, Path preferredSourcePath, String requestedPath) {
        String normalizedContextPath = normalizeContextPath(requestedPath);
        Optional<String> requestedFqcn = normalizeRequestedFqcn(requestedPath);
        LOGGER.log(
                System.Logger.Level.INFO,
                "Resolving context source [requestedPath={0}, normalizedPath={1}, fqcn={2}, preferredSource={3}]",
                requestedPath,
                normalizedContextPath,
                requestedFqcn.orElse("<unresolved>"),
                safePath(preferredSourcePath)
        );
        Optional<Path> localPath = resolveRequestedContextPathIfPresent(projectRoot, preferredSourcePath, requestedPath);
        if (localPath.isPresent()) {
            LOGGER.log(
                    System.Logger.Level.INFO,
                    "Resolved context source from workspace file for {0}: {1}",
                    requestedPath,
                    localPath.get().toAbsolutePath().normalize()
            );
            return readFile(localPath.get());
        }

        Optional<DependencySource> classpathResolvedSource;
        try {
            classpathResolvedSource = resolveDependencySourceViaClasspathIfPresent(
                    projectRoot,
                    preferredSourcePath,
                    requestedPath
            );
        } catch (ClasspathResolutionException exception) {
            throw classpathResolutionFailure(requestedPath, exception);
        }
        if (classpathResolvedSource.isPresent()) {
            LOGGER.log(
                    System.Logger.Level.INFO,
                    "Resolved context source via classpath for {0}: {1}",
                    requestedPath,
                    classpathResolvedSource.get().contextPath()
            );
            return classpathResolvedSource.get().content();
        }

        LOGGER.log(
                System.Logger.Level.WARNING,
                "Unable to resolve context source for {0} after workspace and classpath lookup",
                requestedPath
        );
        throw new IllegalStateException(unresolvedContextMessage(requestedPath));
    }

    private Optional<DependencySource> resolveDependencySourceViaClasspathIfPresent(
            Path projectRoot,
            Path preferredSourcePath,
            String requestedPath
    ) {
        String requestedContextPath = normalizeContextPath(requestedPath);
        LOGGER.log(
                System.Logger.Level.INFO,
                "Attempting classpath context lookup [requestedPath={0}, normalizedPath={1}]",
                requestedPath,
                requestedContextPath
        );
        if (!requestedContextPath.isBlank()) {
            String cached = dependencySourceContentCache.get(requestedContextPath);
            if (cached != null) {
                LOGGER.log(
                        System.Logger.Level.INFO,
                        "Classpath lookup cache hit for context path {0}",
                        requestedContextPath
                );
                return Optional.of(new DependencySource(requestedContextPath, cached));
            }
            if (missingDependencySourcePaths.contains(requestedContextPath)) {
                LOGGER.log(
                        System.Logger.Level.INFO,
                        "Classpath lookup previously failed for context path {0}; skipping retry",
                        requestedContextPath
                );
                return Optional.empty();
            }
        }

        Optional<String> requestedFqcn = normalizeRequestedFqcn(requestedPath);
        if (requestedFqcn.isEmpty()) {
            LOGGER.log(
                    System.Logger.Level.INFO,
                    "Skipping classpath context lookup because FQCN could not be derived from {0}",
                    requestedPath
            );
            return Optional.empty();
        }

        Path normalizedProjectRoot = projectRoot.toAbsolutePath().normalize();
        Path moduleRoot = resolveContextModuleRoot(normalizedProjectRoot, preferredSourcePath);
        LOGGER.log(
                System.Logger.Level.INFO,
                "Classpath resolver input [requestedPath={0}, fqcn={1}, projectRoot={2}, moduleRoot={3}]",
                requestedPath,
                requestedFqcn.get(),
                normalizedProjectRoot,
                moduleRoot
        );
        Optional<ResolvedContextSource> resolved = contextSourceResolver.resolve(
                normalizedProjectRoot,
                moduleRoot,
                requestedFqcn.get()
        );
        if (resolved.isEmpty()) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Classpath resolver returned no source for [requestedPath={0}, fqcn={1}]",
                    requestedPath,
                    requestedFqcn.get()
            );
            if (!requestedContextPath.isBlank()) {
                missingDependencySourcePaths.add(requestedContextPath);
            }
            return Optional.empty();
        }
        String content = resolved.get().content();
        String resolvedContextPath = normalizeContextPath(resolved.get().contextPath());
        if (!resolvedContextPath.isBlank()) {
            dependencySourceContentCache.put(resolvedContextPath, content);
            missingDependencySourcePaths.remove(resolvedContextPath);
        }

        if (!requestedContextPath.isBlank()) {
            dependencySourceContentCache.put(requestedContextPath, content);
            missingDependencySourcePaths.remove(requestedContextPath);
        }

        String contextPath = resolvedContextPath.isBlank()
                ? contextPathFromFqcn(requestedFqcn.get())
                : resolvedContextPath;
        LOGGER.log(
                System.Logger.Level.INFO,
                "Classpath resolver resolved source [requestedPath={0}, fqcn={1}, contextPath={2}]",
                requestedPath,
                requestedFqcn.get(),
                contextPath
        );
        return Optional.of(new DependencySource(contextPath, content));
    }

    private Path resolveContextModuleRoot(Path projectRoot, Path preferredSourcePath) {
        Path normalizedProjectRoot = projectRoot.toAbsolutePath().normalize();
        if (preferredSourcePath != null) {
            Path moduleRoot = findNearestBuildProjectRoot(preferredSourcePath);
            if (moduleRoot != null) {
                Path normalizedModuleRoot = moduleRoot.toAbsolutePath().normalize();
                if (normalizedModuleRoot.startsWith(normalizedProjectRoot)) {
                    LOGGER.log(
                            System.Logger.Level.INFO,
                            "Using module root derived from preferred source path [preferredSource={0}, moduleRoot={1}]",
                            preferredSourcePath.toAbsolutePath().normalize(),
                            normalizedModuleRoot
                    );
                    return normalizedModuleRoot;
                }
            }
        }
        LOGGER.log(
                System.Logger.Level.INFO,
                "Falling back to project root for context classpath resolution: {0}",
                normalizedProjectRoot
        );
        return normalizedProjectRoot;
    }

    private Optional<String> normalizeRequestedFqcn(String requestedPath) {
        if (requestedPath == null || requestedPath.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalizeContextPath(requestedPath);
        if (normalized.isBlank()) {
            return Optional.empty();
        }

        String candidate = normalized.trim();
        if (candidate.startsWith("import ")) {
            candidate = candidate.substring("import ".length()).trim();
        }
        if (candidate.startsWith("static ")) {
            candidate = candidate.substring("static ".length()).trim();
            if (!candidate.endsWith(".*")) {
                int lastDot = candidate.lastIndexOf('.');
                if (lastDot > 0) {
                    candidate = candidate.substring(0, lastDot);
                }
            }
        }
        if (candidate.endsWith(";")) {
            candidate = candidate.substring(0, candidate.length() - 1).trim();
        }
        if (candidate.endsWith(".class")) {
            candidate = candidate.substring(0, candidate.length() - ".class".length());
        }
        if (candidate.endsWith(".*")) {
            return Optional.empty();
        }
        if (candidate.endsWith(".java")) {
            candidate = candidate.substring(0, candidate.length() - ".java".length());
        }
        candidate = candidate.replace('\\', '.').replace('/', '.');
        while (candidate.startsWith(".")) {
            candidate = candidate.substring(1);
        }
        if (candidate.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(candidate);
    }

    private String contextPathFromFqcn(String fqcn) {
        String normalized = fqcn == null ? "" : fqcn.trim();
        int firstDollar = normalized.indexOf('$');
        if (firstDollar >= 0) {
            normalized = normalized.substring(0, firstDollar);
        }
        return normalized.replace('.', '/') + ".java";
    }

    private Optional<Path> resolveRequestedContextPathIfPresent(Path projectRoot, Path preferredSourcePath, String requestedPath) {
        List<Path> candidates = preferredCandidates(projectRoot, preferredSourcePath, requestedPath);
        logCandidatePathsForContextLookup(requestedPath, candidates);
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                LOGGER.log(
                        System.Logger.Level.INFO,
                        "Matched requested context path {0} to workspace file {1}",
                        requestedPath,
                        candidate.toAbsolutePath().normalize()
                );
                return Optional.of(candidate.normalize());
            }
        }

        String normalizedSuffix = normalizeSuffix(requestedPath);
        LOGGER.log(
                System.Logger.Level.INFO,
                "No direct file match for context path {0}; scanning project sources by suffix {1}",
                requestedPath,
                normalizedSuffix
        );
        try {
            Optional<Path> resolvedPath = collectProjectJavaSources(projectRoot).stream()
                    .filter(path -> matchesRequestedSuffix(projectRoot, path, normalizedSuffix))
                    .sorted(preferredPathComparator(projectRoot, preferredSourcePath, normalizedSuffix))
                    .findFirst()
                    .map(Path::normalize);
            if (resolvedPath.isPresent()) {
                LOGGER.log(
                        System.Logger.Level.INFO,
                        "Resolved context path {0} by suffix scan to {1}",
                        requestedPath,
                        resolvedPath.get().toAbsolutePath().normalize()
                );
            } else {
                LOGGER.log(
                        System.Logger.Level.WARNING,
                        "Context path {0} not found in workspace source scan",
                        requestedPath
                );
            }
            return resolvedPath;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to search for context class path " + requestedPath, exception);
        }
    }

    private Optional<String> resolveImportedContextClassPath(Path projectRoot, Path sourcePath, String importTarget) {
        String candidate = importTarget;
        while (candidate.contains(".")) {
            String requestedPath = candidate.replace('.', '/') + ".java";
            Optional<Path> resolvedPath = resolveRequestedContextPathIfPresent(projectRoot, sourcePath, requestedPath);
            if (resolvedPath.isPresent()) {
                return Optional.of(toContextClassPath(projectRoot, resolvedPath.get()));
            }
            if (shouldSearchDependencySources(candidate)) {
                try {
                    Optional<DependencySource> dependencySource = resolveDependencySourceViaClasspathIfPresent(
                            projectRoot,
                            sourcePath,
                            requestedPath
                    );
                    if (dependencySource.isPresent()) {
                        return Optional.of(dependencySource.get().contextPath());
                    }
                } catch (ClasspathResolutionException ignored) {
                    // Imported dependency context is a best-effort hint for the backend.
                }
            }
            int lastDot = candidate.lastIndexOf('.');
            candidate = candidate.substring(0, lastDot);
        }
        return Optional.empty();
    }

    private List<String> resolveStarImportPaths(Path projectRoot, Path sourcePath, String importTarget) {
        Optional<String> importedContextClassPath = resolveImportedContextClassPath(projectRoot, sourcePath, importTarget);
        if (importedContextClassPath.isPresent()) {
            return List.of(importedContextClassPath.get());
        }
        return resolveWildcardImportPaths(projectRoot, importTarget);
    }

    private List<String> resolveWildcardImportPaths(Path projectRoot, String packageName) {
        String packagePath = packageName.replace('.', '/');
        Set<String> resolvedPaths = new LinkedHashSet<>();

        for (Path sourceRoot : JAVA_SOURCE_ROOTS) {
            Path candidateDirectory = projectRoot.resolve(sourceRoot).resolve(packagePath).normalize();
            if (Files.isDirectory(candidateDirectory)) {
                resolvedPaths.addAll(readPackageJavaFiles(projectRoot, candidateDirectory));
            }
        }

        try {
            for (Path packageDirectory : collectPackageDirectories(projectRoot, packagePath)) {
                resolvedPaths.addAll(readPackageJavaFiles(projectRoot, packageDirectory));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to search wildcard import package " + packageName, exception);
        }

        return List.copyOf(resolvedPaths);
    }

    private List<String> readPackageJavaFiles(Path projectRoot, Path directory) {
        try (var paths = Files.list(directory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName() != null && path.getFileName().toString().endsWith(".java"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(path -> toContextClassPath(projectRoot, path))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read package directory " + directory, exception);
        }
    }

    private List<String> extractImportTargets(String sourceCode) {
        List<String> importTargets = new ArrayList<>();
        for (String line : sourceCode.lines().toList()) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("import ") || !trimmed.endsWith(";")) {
                continue;
            }
            String importTarget = trimmed.substring("import ".length(), trimmed.length() - 1).trim();
            if (importTarget.startsWith("static ")) {
                importTarget = importTarget.substring("static ".length()).trim();
                if (!importTarget.endsWith(".*")) {
                    int lastDot = importTarget.lastIndexOf('.');
                    if (lastDot > 0) {
                        importTarget = importTarget.substring(0, lastDot);
                    }
                }
            }
            importTargets.add(importTarget);
        }
        return importTargets;
    }

    private String toContextClassPath(Path projectRoot, Path path) {
        Path normalizedPath = path.normalize();
        if (projectRoot != null && normalizedPath.startsWith(projectRoot.normalize())) {
            Path projectRelative = projectRoot.normalize().relativize(normalizedPath);
            Path preservedRelative = preserveModulePrefix(projectRelative);
            if (preservedRelative != null) {
                return normalizeSeparators(preservedRelative);
            }
        }
        for (int index = 0; index <= normalizedPath.getNameCount() - 3; index++) {
            if (normalizedPath.getName(index).toString().equals("src")
                    && (normalizedPath.getName(index + 1).toString().equals("main")
                    || normalizedPath.getName(index + 1).toString().equals("test"))
                    && normalizedPath.getName(index + 2).toString().equals("java")) {
                return normalizeSeparators(normalizedPath.subpath(index + 3, normalizedPath.getNameCount()));
            }
        }
        return normalizeSeparators(normalizedPath);
    }

    private boolean containsBuildFile(Path directory) {
        return Files.isRegularFile(directory.resolve("pom.xml")) || containsGradleBuildFile(directory);
    }

    private boolean containsGradleBuildFile(Path directory) {
        return GRADLE_BUILD_FILES.stream()
                .map(directory::resolve)
                .anyMatch(Files::isRegularFile);
    }

    private int sourceRootIndex(Path path) {
        for (int index = 0; index <= path.getNameCount() - 3; index++) {
            if (path.getName(index).toString().equals("src")
                    && path.getName(index + 2).toString().equals("java")) {
                return index;
            }
        }
        return -1;
    }

    private List<Path> preferredCandidates(Path projectRoot, Path preferredSourcePath, String requestedPath) {
        List<Path> candidates = new ArrayList<>();
        for (Path normalizedRequestedPath : requestedPathVariants(requestedPath)) {
            candidates.add(projectRoot.resolve(normalizedRequestedPath).normalize());
            for (Path sourceRoot : JAVA_SOURCE_ROOTS) {
                candidates.add(projectRoot.resolve(sourceRoot).resolve(normalizedRequestedPath).normalize());
            }
            for (Path preferredRoot : preferredSearchRoots(projectRoot, preferredSourcePath)) {
                candidates.add(preferredRoot.resolve(normalizedRequestedPath).normalize());
                for (Path sourceRoot : JAVA_SOURCE_ROOTS) {
                    candidates.add(preferredRoot.resolve(sourceRoot).resolve(normalizedRequestedPath).normalize());
                }
            }
        }
        return candidates.stream()
                .distinct()
                .toList();
    }

    private List<Path> preferredSearchRoots(Path projectRoot, Path preferredSourcePath) {
        if (preferredSourcePath == null) {
            return List.of();
        }

        List<Path> roots = new ArrayList<>();
        Path normalizedProjectRoot = projectRoot.normalize();
        Path current = preferredSourcePath.normalize();
        if (Files.isRegularFile(current)) {
            current = current.getParent();
        }
        while (current != null && current.startsWith(normalizedProjectRoot)) {
            if (containsBuildFile(current)) {
                roots.add(current);
            }
            current = current.getParent();
        }
        return roots.stream().distinct().toList();
    }

    private List<Path> requestedPathVariants(String requestedPath) {
        List<String> candidates = new ArrayList<>();
        String normalized = requestedPath == null ? "" : requestedPath.trim().replace('\\', '/');
        if (!normalized.isBlank()) {
            candidates.add(normalized);
            if (!normalized.endsWith(".java")) {
                candidates.add(normalized + ".java");
            }
            if (normalized.contains(".")) {
                String dotted = normalized.replace('.', '/');
                candidates.add(dotted);
                if (!dotted.endsWith(".java")) {
                    candidates.add(dotted + ".java");
                }
            }
        }
        return candidates.stream()
                .map(Path::of)
                .distinct()
                .toList();
    }

    private Comparator<Path> preferredPathComparator(Path projectRoot, Path preferredSourcePath, String normalizedSuffix) {
        List<Path> preferredRoots = preferredSearchRoots(projectRoot, preferredSourcePath);
        return Comparator
                .comparingInt((Path path) -> preferredRootIndex(preferredRoots, path))
                .thenComparingInt(path -> relativeDepth(projectRoot, path, normalizedSuffix))
                .thenComparing(this::normalizeSeparators);
    }

    private int preferredRootIndex(List<Path> preferredRoots, Path path) {
        for (int index = 0; index < preferredRoots.size(); index++) {
            if (path.normalize().startsWith(preferredRoots.get(index))) {
                return index;
            }
        }
        return Integer.MAX_VALUE;
    }

    private int relativeDepth(Path projectRoot, Path path, String normalizedSuffix) {
        Path normalizedRoot = projectRoot.normalize();
        Path normalizedPath = path.normalize();
        if (!normalizedPath.startsWith(normalizedRoot)) {
            return Integer.MAX_VALUE;
        }
        String relativePath = normalizeSeparators(normalizedRoot.relativize(normalizedPath));
        if (!relativePath.endsWith(normalizedSuffix)) {
            return Integer.MAX_VALUE;
        }
        return relativePath.length() - normalizedSuffix.length();
    }

    private boolean matchesRequestedSuffix(Path projectRoot, Path path, String normalizedSuffix) {
        Path normalizedRoot = projectRoot.normalize();
        Path normalizedPath = path.normalize();
        if (!normalizedPath.startsWith(normalizedRoot)) {
            return false;
        }
        String relativePath = normalizeSeparators(normalizedRoot.relativize(normalizedPath));
        if (relativePath.equals(normalizedSuffix)) {
            return true;
        }
        return relativePath.endsWith("/" + normalizedSuffix);
    }

    private List<Path> collectProjectJavaSources(Path projectRoot) throws IOException {
        List<Path> sources = new ArrayList<>();
        Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (shouldSkipDirectory(projectRoot, dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (isProjectJavaSource(projectRoot, file)) {
                    sources.add(file.normalize());
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return sources;
    }

    private List<Path> collectPackageDirectories(Path projectRoot, String packagePath) throws IOException {
        List<Path> directories = new ArrayList<>();
        String mainSuffix = "/src/main/java/" + packagePath;
        String testSuffix = "/src/test/java/" + packagePath;
        Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (shouldSkipDirectory(projectRoot, dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (isSourcePackageDirectory(projectRoot, dir, mainSuffix, testSuffix)) {
                    directories.add(dir.normalize());
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return directories;
    }

    private boolean shouldSkipDirectory(Path projectRoot, Path directory) {
        Path normalizedRoot = projectRoot.normalize();
        Path normalizedDirectory = directory.normalize();
        if (normalizedDirectory.equals(normalizedRoot)) {
            return false;
        }
        Path fileName = normalizedDirectory.getFileName();
        if (fileName == null) {
            return false;
        }
        return SKIPPED_SEARCH_DIRECTORY_NAMES.contains(fileName.toString());
    }

    private boolean isProjectJavaSource(Path projectRoot, Path path) {
        if (!Files.isRegularFile(path) || path.getFileName() == null || !path.getFileName().toString().endsWith(".java")) {
            return false;
        }
        Path normalizedRoot = projectRoot.normalize();
        Path normalizedPath = path.normalize();
        if (!normalizedPath.startsWith(normalizedRoot)) {
            return false;
        }
        String relativePath = normalizeSeparators(normalizedRoot.relativize(normalizedPath));
        return isSourcePath(relativePath);
    }

    private boolean isSourcePackageDirectory(Path projectRoot, Path directory, String mainSuffix, String testSuffix) {
        Path normalizedRoot = projectRoot.normalize();
        Path normalizedDirectory = directory.normalize();
        if (!normalizedDirectory.startsWith(normalizedRoot)) {
            return false;
        }
        String relativePath = normalizeSeparators(normalizedRoot.relativize(normalizedDirectory));
        if (!isSourcePath(relativePath)) {
            return false;
        }
        String normalizedPath = "/" + relativePath;
        return normalizedPath.endsWith(mainSuffix) || normalizedPath.endsWith(testSuffix);
    }

    private boolean isSourcePath(String normalizedRelativePath) {
        return normalizedRelativePath.equals("src/main/java")
                || normalizedRelativePath.equals("src/test/java")
                || normalizedRelativePath.startsWith("src/main/java/")
                || normalizedRelativePath.startsWith("src/test/java/")
                || normalizedRelativePath.contains("/src/main/java/")
                || normalizedRelativePath.contains("/src/test/java/");
    }

    private String normalizeSuffix(String requestedPath) {
        return requestedPathVariants(requestedPath).stream()
                .map(this::normalizeSeparators)
                .findFirst()
                .orElse("");
    }

    private Path preserveModulePrefix(Path projectRelative) {
        for (int index = 0; index <= projectRelative.getNameCount() - 3; index++) {
            if (projectRelative.getName(index).toString().equals("src")
                    && (projectRelative.getName(index + 1).toString().equals("main")
                    || projectRelative.getName(index + 1).toString().equals("test"))
                    && projectRelative.getName(index + 2).toString().equals("java")) {
                if (index == 0) {
                    return null;
                }
                return projectRelative;
            }
        }
        return projectRelative;
    }

    private Path rewriteSourceRoot(Path projectRelative) {
        for (int index = 0; index <= projectRelative.getNameCount() - 3; index++) {
            if (projectRelative.getName(index).toString().equals("src")
                    && projectRelative.getName(index + 1).toString().equals("main")
                    && projectRelative.getName(index + 2).toString().equals("java")) {
                Path prefix = index == 0 ? null : projectRelative.subpath(0, index);
                Path suffix = projectRelative.subpath(index + 3, projectRelative.getNameCount());
                String className = stripJavaExtension(suffix.getFileName().toString()) + "Test.java";
                Path suffixParent = suffix.getParent();
                Path rewrittenSuffix = suffixParent == null ? Path.of(className) : suffixParent.resolve(className);
                Path testSourceRoot = Path.of("src", "test", "java");
                return prefix == null
                        ? testSourceRoot.resolve(rewrittenSuffix)
                        : prefix.resolve(testSourceRoot).resolve(rewrittenSuffix);
            }
        }
        return null;
    }

    private Path rewriteTestRoot(Path projectRelative) {
        for (int index = 0; index <= projectRelative.getNameCount() - 3; index++) {
            if (projectRelative.getName(index).toString().equals("src")
                    && projectRelative.getName(index + 1).toString().equals("test")
                    && projectRelative.getName(index + 2).toString().equals("java")) {
                Path prefix = index == 0 ? null : projectRelative.subpath(0, index);
                Path suffix = projectRelative.subpath(index + 3, projectRelative.getNameCount());
                String className = stripTestSuffix(suffix.getFileName().toString());
                Path suffixParent = suffix.getParent();
                Path rewrittenSuffix = suffixParent == null ? Path.of(className) : suffixParent.resolve(className);
                Path mainSourceRoot = Path.of("src", "main", "java");
                return prefix == null
                        ? mainSourceRoot.resolve(rewrittenSuffix)
                        : prefix.resolve(mainSourceRoot).resolve(rewrittenSuffix);
            }
        }
        return null;
    }

    private String stripTestSuffix(String fileName) {
        for (String suffix : List.of("Test.java", "Tests.java", "IT.java", "ITCase.java")) {
            if (fileName.endsWith(suffix)) {
                return fileName.substring(0, fileName.length() - suffix.length()) + ".java";
            }
        }
        return fileName;
    }

    private String extractPackageName(String content) {
        return content.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("package "))
                .findFirst()
                .map(line -> line.substring("package ".length()).replace(";", "").trim())
                .orElse("");
    }

    private String normalizeSeparators(Path path) {
        return path.toString().replace('\\', '/');
    }

    private boolean shouldSearchDependencySources(String importTarget) {
        return importTarget != null
                && !importTarget.startsWith("java.")
                && !importTarget.startsWith("javax.")
                && !importTarget.startsWith("jdk.")
                && !importTarget.startsWith("sun.")
                && !importTarget.startsWith("com.sun.");
    }

    private String normalizeContextPath(String requestedPath) {
        if (requestedPath == null || requestedPath.isBlank()) {
            return "";
        }
        String normalized = requestedPath.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.startsWith("src/main/java/")) {
            return normalized.substring("src/main/java/".length());
        }
        if (normalized.startsWith("src/test/java/")) {
            return normalized.substring("src/test/java/".length());
        }
        int mainSegment = normalized.indexOf("/src/main/java/");
        if (mainSegment >= 0) {
            return normalized.substring(mainSegment + "/src/main/java/".length());
        }
        int testSegment = normalized.indexOf("/src/test/java/");
        if (testSegment >= 0) {
            return normalized.substring(testSegment + "/src/test/java/".length());
        }
        return normalized;
    }

    private String unresolvedContextMessage(String requestedPath) {
        return "Unable to resolve requested context class path " + requestedPath
                + ". Checked workspace sources, classpath dependency jars, and decompiled class files. "
                + "Ensure the class is on the module test classpath and dependency artifacts are available.";
    }

    private IllegalStateException classpathResolutionFailure(
            String requestedPath,
            ClasspathResolutionException exception
    ) {
        ResolutionFailure failure = exception.failure();
        if (failure == null) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Classpath resolver failed without structured failure for {0}: {1}",
                    requestedPath,
                    exception.getMessage()
            );
            return new IllegalStateException(unresolvedContextMessage(requestedPath), exception);
        }

        LOGGER.log(
                System.Logger.Level.WARNING,
                "Classpath resolver failure for {0} [category={1}, tool={2}, moduleRoot={3}, action={4}, output={5}]",
                requestedPath,
                failure.category(),
                failure.buildTool(),
                failure.moduleRoot(),
                failure.actionSummary(),
                failure.outputSnippet()
        );
        String message = unresolvedContextMessage(requestedPath)
                + " Classpath resolver failure: "
                + failure.category()
                + " [tool=" + failure.buildTool()
                + ", moduleRoot=" + failure.moduleRoot()
                + ", action=" + failure.actionSummary()
                + ", output=" + failure.outputSnippet()
                + "]";
        return new IllegalStateException(message, exception);
    }

    private void logCandidatePathsForContextLookup(String requestedPath, List<Path> candidates) {
        if (!LOGGER.isLoggable(System.Logger.Level.DEBUG)) {
            return;
        }
        LOGGER.log(
                System.Logger.Level.DEBUG,
                "Workspace candidate paths for {0}: {1}",
                requestedPath,
                candidates.size()
        );
        int maxEntries = Math.min(candidates.size(), 25);
        for (int index = 0; index < maxEntries; index++) {
            LOGGER.log(
                    System.Logger.Level.DEBUG,
                    "candidate[{0}]={1}",
                    index,
                    candidates.get(index).toAbsolutePath().normalize()
            );
        }
        if (candidates.size() > maxEntries) {
            LOGGER.log(
                    System.Logger.Level.DEBUG,
                    "... omitted {0} additional candidates",
                    candidates.size() - maxEntries
            );
        }
    }

    private String safePath(Path path) {
        if (path == null) {
            return "<none>";
        }
        return normalizeSeparators(path.toAbsolutePath().normalize());
    }

    @FunctionalInterface
    interface ContextSourceResolver {
        Optional<ResolvedContextSource> resolve(Path projectRoot, Path moduleRoot, String requestedFqcn);
    }

    record ResolvedContextSource(String contextPath, String content) {
    }

    private static ContextSourceResolver defaultContextSourceResolver() {
        BuildToolClassResolutionService classResolutionService = new BuildToolClassResolutionService();
        return (projectRoot, moduleRoot, requestedFqcn) -> {
            ResolutionOptions options = new ResolutionOptions(List.of(), false, true);
            Optional<ResolvedSource> resolvedSource = classResolutionService.resolveSourceByFqcn(
                    requestedFqcn,
                    projectRoot,
                    moduleRoot,
                    options
            );
            if (resolvedSource.isEmpty()) {
                return Optional.empty();
            }

            ResolvedSource source = resolvedSource.get();
            String contextPath = source.fqcn();
            int firstDollar = contextPath.indexOf('$');
            if (firstDollar >= 0) {
                contextPath = contextPath.substring(0, firstDollar);
            }
            contextPath = contextPath.replace('.', '/') + ".java";
            return Optional.of(new ResolvedContextSource(contextPath, source.sourceText()));
        };
    }

    private record DependencySource(String contextPath, String content) {
    }
}
