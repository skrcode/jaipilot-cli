package com.jaipilot.cli.classpath;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildToolClassResolutionServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void compileFallbackRunsOnlyOncePerFingerprint() throws Exception {
        Path projectRoot = tempDir.resolve("repo");
        Path moduleRoot = projectRoot;
        Files.createDirectories(moduleRoot.resolve("target/classes/com/acme"));
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");

        Path compiledOutput = moduleRoot.resolve("target/classes");
        Path sourceRoot = moduleRoot.resolve("src/main/java");
        Files.createDirectories(sourceRoot.resolve("com/acme"));
        Files.write(compiledOutput.resolve("com/acme/Widget.class"), new byte[] {1, 2, 3});
        Files.writeString(sourceRoot.resolve("com/acme/Widget.java"), "package com.acme; class Widget {}\n");

        AtomicInteger forceCompileCount = new AtomicInteger();
        CompileCapableClasspathResolver stubMavenResolver = new CompileCapableClasspathResolver() {
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
                if (forceCompile) {
                    forceCompileCount.incrementAndGet();
                }
                return new ResolvedClasspath(
                        BuildToolType.MAVEN,
                        projectRoot,
                        moduleRoot,
                        List.of(compiledOutput),
                        forceCompile ? List.of(compiledOutput) : List.of(),
                        List.of(),
                        List.of(sourceRoot),
                        List.of(),
                        "fingerprint-1"
                );
            }
        };

        BuildToolClasspathResolver classpathResolver = new BuildToolClasspathResolver(
                new BuildToolDetector(),
                stubMavenResolver,
                stubMavenResolver
        );
        BuildToolClassResolutionService service = new BuildToolClassResolutionService(
                classpathResolver,
                new ClasspathClassLocator(),
                ConcurrentHashMap.newKeySet()
        );

        ResolutionOptions options = new ResolutionOptions(List.of(), true, false);

        ClassResolutionResult first = service.locate("com.acme.Widget", projectRoot, moduleRoot, options);
        ClassResolutionResult second = service.locate("com.acme.Widget", projectRoot, moduleRoot, options);

        assertEquals(LocationKind.PROJECT_MAIN_CLASS, first.kind());
        assertEquals(LocationKind.NOT_FOUND, second.kind());
        assertEquals(1, forceCompileCount.get());
    }
}
