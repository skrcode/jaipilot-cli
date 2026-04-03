package com.jaipilot.cli.classpath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultSourceResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesWorkspaceSourceFromMappedPath() throws Exception {
        Path sourcePath = tempDir.resolve("src/main/java/com/example/Foo.java");
        Files.createDirectories(sourcePath.getParent());
        Files.writeString(sourcePath, "package com.example; class Foo {}\n");

        ClassResolutionResult classResult = new ClassResolutionResult(
                "com.example.Foo",
                LocationKind.PROJECT_MAIN_CLASS,
                tempDir,
                "com/example/Foo.class",
                Optional.of(sourcePath),
                Optional.empty()
        );

        DefaultSourceResolver resolver = new DefaultSourceResolver(tempDir, tempDir);
        Optional<ResolvedSource> source = resolver.resolveSource(classResult, ResolutionOptions.defaults());

        assertTrue(source.isPresent());
        assertEquals(SourceOrigin.WORKSPACE_FILE, source.get().origin());
        assertEquals("com.example.Foo", source.get().fqcn());
        assertTrue(source.get().sourceText().contains("class Foo"));
    }

    @Test
    void resolvesExternalSourceFromSiblingSourcesJar() throws Exception {
        Path dependencyJar = tempDir.resolve("repo/acme-utils-1.0.0.jar");
        Path sourcesJar = tempDir.resolve("repo/acme-utils-1.0.0-sources.jar");
        Files.createDirectories(dependencyJar.getParent());
        Files.write(dependencyJar, new byte[] {0, 1});
        createSourcesJar(sourcesJar, "com/example/Util.java", "package com.example; class Util {}\n");

        ClassResolutionResult classResult = new ClassResolutionResult(
                "com.example.Util",
                LocationKind.EXTERNAL_JAR,
                dependencyJar,
                "com/example/Util.class",
                Optional.empty(),
                Optional.of(new MavenCoordinates("com.example", "acme-utils", "1.0.0"))
        );

        ResolutionOptions options = new ResolutionOptions(List.of(), true, true);
        DefaultSourceResolver resolver = new DefaultSourceResolver(tempDir, tempDir);

        Optional<ResolvedSource> source = resolver.resolveSource(classResult, options);

        assertTrue(source.isPresent());
        assertEquals(SourceOrigin.SOURCES_JAR, source.get().origin());
        assertEquals(sourcesJar.toAbsolutePath().normalize(), source.get().sourceContainer());
        assertTrue(source.get().sourceText().contains("class Util"));
    }

    private static void createSourcesJar(Path jarPath, String entryName, String sourceText) throws IOException {
        try (ZipOutputStream outputStream = new ZipOutputStream(Files.newOutputStream(jarPath))) {
            outputStream.putNextEntry(new ZipEntry(entryName));
            outputStream.write(sourceText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            outputStream.closeEntry();
        }
    }
}
