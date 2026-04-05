package com.jaipilot.cli.classpath;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.api.SinkReturns;

final class CfrDecompiler {

    private static final System.Logger LOGGER = System.getLogger(CfrDecompiler.class.getName());

    
    Optional<String> decompile(Path classContainer, String classEntryPath) {
        if (classContainer == null || classEntryPath == null || classEntryPath.isBlank()) {
            return Optional.empty();
        }

        Path normalizedContainer = classContainer.toAbsolutePath().normalize();
        if (Files.isDirectory(normalizedContainer)) {
            Path classFile = normalizedContainer.resolve(classEntryPath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(classFile)) {
                return Optional.empty();
            }
            return decompileClassFile(classFile);
        }

        String fileName = normalizedContainer.getFileName() == null
                ? ""
                : normalizedContainer.getFileName().toString();
        if (fileName.endsWith(".jar")) {
            return decompileFromJar(normalizedContainer, classEntryPath);
        }
        if (fileName.endsWith(".class") && Files.isRegularFile(normalizedContainer)) {
            return decompileClassFile(normalizedContainer);
        }
        return Optional.empty();
    }

    private Optional<String> decompileFromJar(Path jarPath, String classEntryPath) {
        Path tempDirectory = null;
        try {
            tempDirectory = Files.createTempDirectory("jaipilot-cfr-");
            ExtractedClass extractedClass = extractClassFromJar(jarPath, classEntryPath, tempDirectory).orElse(null);
            if (extractedClass == null) {
                return Optional.empty();
            }
            return decompileClassFile(extractedClass.classFilePath());
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.DEBUG, "cfr extraction failed for {0}: {1}", jarPath, exception.getMessage());
            return Optional.empty();
        } finally {
            deleteRecursively(tempDirectory);
        }
    }

    private Optional<ExtractedClass> extractClassFromJar(Path jarPath, String classEntryPath, Path tempDirectory) throws IOException {
        String normalizedEntryPath = normalizeEntryPath(classEntryPath);
        if (normalizedEntryPath.isBlank()) {
            return Optional.empty();
        }

        String outerEntryPath = outerClassEntryPath(normalizedEntryPath);
        try (ZipFile zipFile = new ZipFile(jarPath.toFile())) {
            ZipEntry primaryEntry = findPrimaryEntry(zipFile, normalizedEntryPath, outerEntryPath);
            if (primaryEntry == null) {
                return Optional.empty();
            }

            String outerPrefix = outerEntryPath.substring(0, outerEntryPath.length() - ".class".length());
            Set<String> entriesToExtract = new LinkedHashSet<>();
            entriesToExtract.add(primaryEntry.getName());
            entriesToExtract.add(outerEntryPath);
            for (ZipEntry entry : List.copyOf(zipFile.stream().toList())) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name.startsWith(outerPrefix + "$") && name.endsWith(".class")) {
                    entriesToExtract.add(name);
                }
            }

            Path primaryPath = null;
            for (String entryName : entriesToExtract) {
                ZipEntry entry = zipFile.getEntry(entryName);
                if (entry == null || entry.isDirectory()) {
                    continue;
                }
                Path extractedPath = extractEntry(zipFile, entry, tempDirectory);
                if (entry.getName().equals(primaryEntry.getName())) {
                    primaryPath = extractedPath;
                }
            }
            return Optional.ofNullable(primaryPath).map(ExtractedClass::new);
        }
    }

    private ZipEntry findPrimaryEntry(ZipFile zipFile, String normalizedEntryPath, String outerEntryPath) {
        ZipEntry outer = zipFile.getEntry(outerEntryPath);
        if (outer != null && !outer.isDirectory()) {
            return outer;
        }
        ZipEntry requested = zipFile.getEntry(normalizedEntryPath);
        if (requested != null && !requested.isDirectory()) {
            return requested;
        }
        return null;
    }

    private Path extractEntry(ZipFile zipFile, ZipEntry entry, Path outputRoot) throws IOException {
        Path outputPath = outputRoot.resolve(entry.getName()).toAbsolutePath().normalize();
        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (InputStream inputStream = zipFile.getInputStream(entry)) {
            Files.copy(inputStream, outputPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return outputPath;
    }

    private Optional<String> decompileClassFile(Path classFilePath) {
        List<String> decompiledSource = new ArrayList<>();
        List<String> exceptions = new ArrayList<>();

        try {
            CfrDriver cfrDriver = new CfrDriver.Builder()
                    .withOptions(Map.of(
                            "showversion", "false",
                            "silent", "true"
                    ))
                    .withOutputSink(new CfrOutputSink(decompiledSource, exceptions))
                    .build();

            cfrDriver.analyse(List.of(classFilePath.toString()));
        } catch (Exception exception) {
            LOGGER.log(System.Logger.Level.DEBUG, "cfr decompilation failed for {0}: {1}", classFilePath, exception.getMessage());
            return Optional.empty();
        }

        if (decompiledSource.isEmpty()) {
            if (!exceptions.isEmpty()) {
                LOGGER.log(System.Logger.Level.DEBUG, "cfr did not produce Java source for {0}: {1}", classFilePath, exceptions.get(0));
            }
            return Optional.empty();
        }

        return decompiledSource.stream()
                .filter(source -> source != null && !source.isBlank())
                .findFirst()
                .map(this::normalizeLineEndings);
    }

    private String normalizeEntryPath(String classEntryPath) {
        String normalized = classEntryPath.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private String outerClassEntryPath(String classEntryPath) {
        int firstDollar = classEntryPath.indexOf('$');
        if (firstDollar < 0) {
            return classEntryPath;
        }
        int classSuffix = classEntryPath.lastIndexOf(".class");
        if (classSuffix < 0) {
            return classEntryPath.substring(0, firstDollar);
        }
        return classEntryPath.substring(0, firstDollar) + ".class";
    }

    private String normalizeLineEndings(String source) {
        return source.replace("\r\n", "\n").replace('\r', '\n');
    }

    private void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(candidate -> {
                try {
                    Files.deleteIfExists(candidate);
                } catch (IOException ignored) {
                    // Best-effort cleanup for temporary decompiler files.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup for temporary decompiler files.
        }
    }

    private record ExtractedClass(Path classFilePath) {
    }

    private static final class CfrOutputSink implements OutputSinkFactory {

        private final List<String> decompiledSource;
        private final List<String> exceptions;

        CfrOutputSink(List<String> decompiledSource, List<String> exceptions) {
            this.decompiledSource = decompiledSource;
            this.exceptions = exceptions;
        }

        @Override
        public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> available) {
            if (sinkType == SinkType.JAVA) {
                if (available.contains(SinkClass.DECOMPILED)) {
                    return List.of(SinkClass.DECOMPILED);
                }
                if (available.contains(SinkClass.STRING)) {
                    return List.of(SinkClass.STRING);
                }
                return List.of();
            }

            if (sinkType == SinkType.EXCEPTION && available.contains(SinkClass.EXCEPTION_MESSAGE)) {
                return List.of(SinkClass.EXCEPTION_MESSAGE);
            }
            if (sinkType == SinkType.EXCEPTION && available.contains(SinkClass.STRING)) {
                return List.of(SinkClass.STRING);
            }
            return List.of();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Sink<T> getSink(SinkType sinkType, SinkClass sinkClass) {
            if (sinkType == SinkType.JAVA && sinkClass == SinkClass.DECOMPILED) {
                return sinkable -> decompiledSource.add(((SinkReturns.Decompiled) sinkable).getJava());
            }
            if (sinkType == SinkType.JAVA && sinkClass == SinkClass.STRING) {
                return sinkable -> decompiledSource.add(String.valueOf(sinkable));
            }
            if (sinkType == SinkType.EXCEPTION && sinkClass == SinkClass.EXCEPTION_MESSAGE) {
                return sinkable -> exceptions.add(((SinkReturns.ExceptionMessage) sinkable).getMessage());
            }
            if (sinkType == SinkType.EXCEPTION && sinkClass == SinkClass.STRING) {
                return sinkable -> exceptions.add(String.valueOf(sinkable));
            }
            return sinkable -> {
            };
        }
    }
}
