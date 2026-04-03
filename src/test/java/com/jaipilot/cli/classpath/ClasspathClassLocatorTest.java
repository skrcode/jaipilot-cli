package com.jaipilot.cli.classpath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClasspathClassLocatorTest {

    @TempDir
    Path tempDir;

    private final ClasspathClassLocator locator = new ClasspathClassLocator();

    @Test
    void locatesProjectMainClassAndMapsInnerClassToOuterSource() throws Exception {
        Path projectRoot = tempDir.resolve("repo");
        Path moduleRoot = projectRoot;
        Path mainOutputDir = moduleRoot.resolve("target/classes");
        Path mainSourceRoot = moduleRoot.resolve("src/main/java");

        Files.createDirectories(mainOutputDir.resolve("com/foo"));
        Files.createDirectories(mainSourceRoot.resolve("com/foo"));
        Files.write(mainOutputDir.resolve("com/foo/Bar$Builder.class"), new byte[] {0, 1, 2});
        Files.writeString(mainSourceRoot.resolve("com/foo/Bar.java"), "package com.foo; class Bar {}\n");

        ResolvedClasspath classpath = new ResolvedClasspath(
                BuildToolType.MAVEN,
                projectRoot,
                moduleRoot,
                List.of(mainOutputDir),
                List.of(mainOutputDir),
                List.of(),
                List.of(mainSourceRoot),
                List.of(),
                "fp-main"
        );

        ClassResolutionResult result = locator.locate("com.foo.Bar$Builder", classpath);

        assertEquals(LocationKind.PROJECT_MAIN_CLASS, result.kind());
        assertEquals(mainOutputDir.toAbsolutePath().normalize(), result.containerPath());
        assertTrue(result.mappedSourcePath().isPresent());
        assertEquals(
                mainSourceRoot.resolve("com/foo/Bar.java").toAbsolutePath().normalize(),
                result.mappedSourcePath().get()
        );
    }

    @Test
    void locatesExternalJarAndExtractsMavenCoordinates() throws Exception {
        Path projectRoot = tempDir.resolve("repo");
        Path moduleRoot = projectRoot;
        Path jarPath = moduleRoot.resolve("libs/acme-utils-1.2.3.jar");
        Files.createDirectories(jarPath.getParent());
        createJar(jarPath,
                "org/acme/Util.class", new byte[] {1, 2, 3},
                "META-INF/maven/org.acme/acme-utils/pom.properties", pomProperties("org.acme", "acme-utils", "1.2.3")
        );

        ResolvedClasspath classpath = new ResolvedClasspath(
                BuildToolType.MAVEN,
                projectRoot,
                moduleRoot,
                List.of(jarPath),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "fp-jar"
        );

        ClassResolutionResult result = locator.locate("org.acme.Util", classpath);

        assertEquals(LocationKind.EXTERNAL_JAR, result.kind());
        assertEquals(jarPath.toAbsolutePath().normalize(), result.containerPath());
        assertTrue(result.externalCoordinates().isPresent());
        assertEquals("org.acme", result.externalCoordinates().get().groupId());
        assertEquals("acme-utils", result.externalCoordinates().get().artifactId());
        assertEquals("1.2.3", result.externalCoordinates().get().version());
    }

    @Test
    void picksFirstJarOnClasspathWhenSameClassExistsInMultipleVersions() throws Exception {
        Path projectRoot = tempDir.resolve("repo");
        Path moduleRoot = projectRoot;
        Path firstJar = moduleRoot.resolve("libs/acme-utils-1.0.0.jar");
        Path secondJar = moduleRoot.resolve("libs/acme-utils-2.0.0.jar");
        Files.createDirectories(firstJar.getParent());
        createJar(
                firstJar,
                "org/acme/Util.class",
                new byte[] {1},
                "META-INF/maven/org.acme/acme-utils/pom.properties",
                pomProperties("org.acme", "acme-utils", "1.0.0")
        );
        createJar(
                secondJar,
                "org/acme/Util.class",
                new byte[] {2},
                "META-INF/maven/org.acme/acme-utils/pom.properties",
                pomProperties("org.acme", "acme-utils", "2.0.0")
        );

        ResolvedClasspath classpath = new ResolvedClasspath(
                BuildToolType.MAVEN,
                projectRoot,
                moduleRoot,
                List.of(firstJar, secondJar),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "fp-duplicate"
        );

        ClassResolutionResult result = locator.locate("org.acme.Util", classpath);

        assertEquals(LocationKind.EXTERNAL_JAR, result.kind());
        assertEquals(firstJar.toAbsolutePath().normalize(), result.containerPath());
        assertTrue(result.externalCoordinates().isPresent());
        assertEquals("1.0.0", result.externalCoordinates().get().version());
    }

    @Test
    void returnsNotFoundWhenClassIsMissing() {
        ResolvedClasspath classpath = new ResolvedClasspath(
                BuildToolType.GRADLE,
                tempDir,
                tempDir,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "fp-missing"
        );

        ClassResolutionResult result = locator.locate("com.example.Missing", classpath);

        assertEquals(LocationKind.NOT_FOUND, result.kind());
    }

    private static byte[] pomProperties(String groupId, String artifactId, String version) {
        Properties properties = new Properties();
        properties.setProperty("groupId", groupId);
        properties.setProperty("artifactId", artifactId);
        properties.setProperty("version", version);
        try (var out = new java.io.ByteArrayOutputStream()) {
            properties.store(out, null);
            return out.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void createJar(Path jarPath, String classEntry, byte[] classBytes, String metadataEntry, byte[] metadataBytes)
            throws IOException {
        try (ZipOutputStream outputStream = new ZipOutputStream(Files.newOutputStream(jarPath))) {
            outputStream.putNextEntry(new ZipEntry(classEntry));
            outputStream.write(classBytes);
            outputStream.closeEntry();

            outputStream.putNextEntry(new ZipEntry(metadataEntry));
            outputStream.write(metadataBytes);
            outputStream.closeEntry();
        }
    }
}
