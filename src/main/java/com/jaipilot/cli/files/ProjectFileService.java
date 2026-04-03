package com.jaipilot.cli.files;

import com.jaipilot.cli.classpath.BuildToolClassResolutionService;
import com.jaipilot.cli.classpath.ClassResolutionResult;
import com.jaipilot.cli.classpath.LocationKind;
import com.jaipilot.cli.classpath.ResolutionOptions;
import com.jaipilot.cli.classpath.ResolvedSource;
import com.jaipilot.cli.process.BuildTool;
import com.jaipilot.cli.util.JavaSourceFormatter;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ProjectFileService {

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
    private static final List<String> SOURCE_JAR_SUFFIXES = List.of(
            "-sources.jar",
            "-source.jar"
    );
    private static final Pattern MAVEN_LOCAL_REPOSITORY_PATTERN = Pattern.compile(
            "(?is)<localRepository>\\s*([^<]+?)\\s*</localRepository>"
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

    private final List<Path> dependencySourceSearchRoots;
    private final ContextSourceResolver contextSourceResolver;
    private final Map<String, String> dependencySourceContentCache = new HashMap<>();
    private final Set<String> missingDependencySourcePaths = new HashSet<>();
    private List<Path> dependencySourceJars;

    public ProjectFileService() {
        this(defaultDependencySourceSearchRoots(), defaultContextSourceResolver());
    }

    ProjectFileService(List<Path> dependencySourceSearchRoots) {
        this(dependencySourceSearchRoots, defaultContextSourceResolver());
    }

    ProjectFileService(List<Path> dependencySourceSearchRoots, ContextSourceResolver contextSourceResolver) {
        this.dependencySourceSearchRoots = dependencySourceSearchRoots == null
                ? List.of()
                : dependencySourceSearchRoots.stream()
                        .filter(path -> path != null && !path.toString().isBlank())
                        .map(Path::normalize)
                        .distinct()
                        .toList();
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
        return requestedPaths.stream()
                .map(path -> readRequestedContextSource(projectRoot, preferredSourcePath, path))
                .toList();
    }

    public List<String> readCachedContextEntries(Path projectRoot, List<String> contextPaths) {
        if (contextPaths == null || contextPaths.isEmpty()) {
            return List.of();
        }

        return contextPaths.stream()
                .map(path -> path + " =\n" + readRequestedContextSource(projectRoot, null, path))
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
        dependencySourceJars = null;
    }

    public String stripJavaExtension(String fileName) {
        if (fileName.endsWith(".java")) {
            return fileName.substring(0, fileName.length() - ".java".length());
        }
        return fileName;
    }

    private String readRequestedContextSource(Path projectRoot, Path preferredSourcePath, String requestedPath) {
        Optional<Path> localPath = resolveRequestedContextPathIfPresent(projectRoot, preferredSourcePath, requestedPath);
        if (localPath.isPresent()) {
            return readFile(localPath.get());
        }

        Optional<DependencySource> dependencySource = resolveDependencySourceIfPresent(requestedPath);
        if (dependencySource.isPresent()) {
            return dependencySource.get().content();
        }

        Optional<DependencySource> classpathResolvedSource = resolveDependencySourceViaClasspathIfPresent(
                projectRoot,
                preferredSourcePath,
                requestedPath
        );
        if (classpathResolvedSource.isPresent()) {
            return classpathResolvedSource.get().content();
        }

        throw new IllegalStateException(
                "Unable to resolve requested context class path " + requestedPath
                        + ". Checked workspace sources and dependency sources. "
                        + "Ensure the class is on the module test classpath (profiles/build args) and dependency sources are available."
        );
    }

    private Optional<DependencySource> resolveDependencySourceViaClasspathIfPresent(
            Path projectRoot,
            Path preferredSourcePath,
            String requestedPath
    ) {
        Optional<String> requestedFqcn = normalizeRequestedFqcn(requestedPath);
        if (requestedFqcn.isEmpty()) {
            return Optional.empty();
        }

        Path normalizedProjectRoot = projectRoot.toAbsolutePath().normalize();
        Path moduleRoot = resolveContextModuleRoot(normalizedProjectRoot, preferredSourcePath);
        try {
            Optional<ResolvedContextSource> resolved = contextSourceResolver.resolve(
                    normalizedProjectRoot,
                    moduleRoot,
                    requestedFqcn.get()
            );
            if (resolved.isEmpty()) {
                return Optional.empty();
            }
            String content = resolved.get().content();
            String resolvedContextPath = normalizeContextPath(resolved.get().contextPath());
            if (!resolvedContextPath.isBlank()) {
                dependencySourceContentCache.put(resolvedContextPath, content);
                missingDependencySourcePaths.remove(resolvedContextPath);
            }

            String requestedContextPath = normalizeContextPath(requestedPath);
            if (!requestedContextPath.isBlank()) {
                dependencySourceContentCache.put(requestedContextPath, content);
                missingDependencySourcePaths.remove(requestedContextPath);
            }

            String contextPath = resolvedContextPath.isBlank()
                    ? contextPathFromFqcn(requestedFqcn.get())
                    : resolvedContextPath;
            return Optional.of(new DependencySource(contextPath, content));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private Path resolveContextModuleRoot(Path projectRoot, Path preferredSourcePath) {
        Path normalizedProjectRoot = projectRoot.toAbsolutePath().normalize();
        if (preferredSourcePath != null) {
            Path moduleRoot = findNearestBuildProjectRoot(preferredSourcePath);
            if (moduleRoot != null) {
                Path normalizedModuleRoot = moduleRoot.toAbsolutePath().normalize();
                if (normalizedModuleRoot.startsWith(normalizedProjectRoot)) {
                    return normalizedModuleRoot;
                }
            }
        }
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
        for (Path candidate : preferredCandidates(projectRoot, preferredSourcePath, requestedPath)) {
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate.normalize());
            }
        }

        String normalizedSuffix = normalizeSuffix(requestedPath);
        try {
            return collectProjectJavaSources(projectRoot).stream()
                    .filter(path -> matchesRequestedSuffix(projectRoot, path, normalizedSuffix))
                    .sorted(preferredPathComparator(projectRoot, preferredSourcePath, normalizedSuffix))
                    .findFirst()
                    .map(Path::normalize);
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
                Optional<DependencySource> dependencySource = resolveDependencySourceIfPresent(requestedPath);
                if (dependencySource.isPresent()) {
                    return Optional.of(dependencySource.get().contextPath());
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

    private Optional<DependencySource> resolveDependencySourceIfPresent(String requestedPath) {
        for (Path candidatePath : requestedPathVariants(requestedPath)) {
            String candidate = normalizeContextPath(normalizeSeparators(candidatePath));
            if (candidate.isBlank() || !candidate.endsWith(".java")) {
                continue;
            }
            Optional<String> content = readDependencySourceContentIfPresent(candidate);
            if (content.isPresent()) {
                return Optional.of(new DependencySource(candidate, content.get()));
            }
        }
        return Optional.empty();
    }

    private Optional<String> readDependencySourceContentIfPresent(String contextPath) {
        String normalizedContextPath = normalizeContextPath(contextPath);
        if (normalizedContextPath.isBlank()) {
            return Optional.empty();
        }
        if (dependencySourceContentCache.containsKey(normalizedContextPath)) {
            return Optional.of(dependencySourceContentCache.get(normalizedContextPath));
        }
        if (missingDependencySourcePaths.contains(normalizedContextPath)) {
            return Optional.empty();
        }

        for (Path sourceJar : dependencySourceJars()) {
            Optional<String> content = readSourceJarEntry(sourceJar, normalizedContextPath);
            if (content.isPresent()) {
                dependencySourceContentCache.put(normalizedContextPath, content.get());
                return content;
            }
        }

        missingDependencySourcePaths.add(normalizedContextPath);
        return Optional.empty();
    }

    private List<Path> dependencySourceJars() {
        if (dependencySourceJars != null) {
            return dependencySourceJars;
        }

        List<Path> jars = new ArrayList<>();
        for (Path searchRoot : dependencySourceSearchRoots) {
            if (!Files.isDirectory(searchRoot)) {
                continue;
            }
            try (var paths = Files.walk(searchRoot)) {
                paths.filter(Files::isRegularFile)
                        .filter(this::isSourceJar)
                        .forEach(jars::add);
            } catch (IOException exception) {
                // Ignore unreadable dependency caches and continue with the remaining roots.
            }
        }
        dependencySourceJars = jars.stream().distinct().toList();
        return dependencySourceJars;
    }

    private boolean isSourceJar(Path path) {
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
        for (String suffix : SOURCE_JAR_SUFFIXES) {
            if (fileName.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private Optional<String> readSourceJarEntry(Path sourceJarPath, String contextPath) {
        try (ZipFile zipFile = new ZipFile(sourceJarPath.toFile())) {
            ZipEntry entry = zipFile.getEntry(contextPath);
            if (entry == null || entry.isDirectory()) {
                return Optional.empty();
            }
            try (InputStream inputStream = zipFile.getInputStream(entry)) {
                return Optional.of(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException exception) {
            return Optional.empty();
        }
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

    private static List<Path> defaultDependencySourceSearchRoots() {
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            return List.of();
        }

        Path homePath = Path.of(userHome);
        List<Path> roots = new ArrayList<>();
        String explicitMavenRepo = firstNonBlank(
                System.getProperty("maven.repo.local"),
                System.getenv("MAVEN_REPO_LOCAL"),
                System.getenv("M2_REPO")
        );
        if (explicitMavenRepo != null) {
            roots.add(resolveConfiguredPath(homePath, explicitMavenRepo));
        }
        mavenLocalRepositoryFromSettings(homePath).ifPresent(roots::add);
        roots.add(homePath.resolve(".m2").resolve("repository"));

        String gradleHome = firstNonBlank(System.getenv("GRADLE_USER_HOME"), System.getenv("GRADLE_HOME"));
        Path gradleBasePath = gradleHome == null
                ? homePath.resolve(".gradle")
                : Path.of(gradleHome);
        roots.add(gradleBasePath.resolve("caches").resolve("modules-2").resolve("files-2.1"));
        return roots.stream()
                .map(Path::normalize)
                .distinct()
                .toList();
    }

    private static Optional<Path> mavenLocalRepositoryFromSettings(Path homePath) {
        Path settingsPath = homePath.resolve(".m2").resolve("settings.xml");
        if (!Files.isRegularFile(settingsPath)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(settingsPath, StandardCharsets.UTF_8);
            Matcher matcher = MAVEN_LOCAL_REPOSITORY_PATTERN.matcher(content);
            if (!matcher.find()) {
                return Optional.empty();
            }
            return Optional.of(resolveConfiguredPath(homePath, matcher.group(1)));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static Path resolveConfiguredPath(Path homePath, String configuredPath) {
        String normalized = configuredPath == null ? "" : configuredPath.trim();
        if (normalized.isBlank()) {
            return homePath.resolve(".m2").resolve("repository").normalize();
        }

        String userHome = homePath.toString();
        normalized = normalized.replace("${user.home}", userHome);
        String envHome = System.getenv("HOME");
        if (envHome != null && !envHome.isBlank()) {
            normalized = normalized.replace("${env.HOME}", envHome);
        } else {
            normalized = normalized.replace("${env.HOME}", userHome);
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

    private static String firstNonBlank(String primary, String fallback) {
        return firstNonBlank(new String[] {primary, fallback});
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
            ClassResolutionResult classResult = classResolutionService.locate(
                    requestedFqcn,
                    projectRoot,
                    moduleRoot,
                    options
            );
            if (classResult.kind() == LocationKind.NOT_FOUND) {
                return Optional.empty();
            }
            Optional<ResolvedSource> resolvedSource = classResolutionService.resolveSource(
                    classResult,
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
