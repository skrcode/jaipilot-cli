package com.jaipilot.cli.files;

import com.jaipilot.cli.process.BuildTool;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectFileServiceTest {

    private final ProjectFileService projectFileService = new ProjectFileService();

    @TempDir
    Path tempDir;

    @Test
    void deriveGeneratedTestPathRewritesStandardMavenSourceRoots() {
        Path projectRoot = Path.of("/tmp/project");
        Path cutPath = projectRoot.resolve("src/main/java/com/example/CrashController.java");

        Path outputPath = projectFileService.deriveGeneratedTestPath(projectRoot, cutPath);

        assertEquals(
                projectRoot.resolve("src/test/java/com/example/CrashControllerTest.java"),
                outputPath
        );
    }

    @Test
    void deriveGeneratedTestPathPreservesModulePrefix() {
        Path projectRoot = Path.of("/tmp/project");
        Path cutPath = projectRoot.resolve("module-a/src/main/java/com/example/CrashController.java");

        Path outputPath = projectFileService.deriveGeneratedTestPath(projectRoot, cutPath);

        assertEquals(
                projectRoot.resolve("module-a/src/test/java/com/example/CrashControllerTest.java"),
                outputPath
        );
    }

    @Test
    void deriveGradleProjectPathUsesModulePrefixBeforeSourceRoot() {
        Path projectRoot = Path.of("/tmp/project");
        Path cutPath = projectRoot.resolve("storage/api/src/main/java/com/example/CrashController.java");

        assertEquals(":storage:api", projectFileService.deriveGradleProjectPath(projectRoot, cutPath));
        assertEquals("", projectFileService.deriveGradleProjectPath(projectRoot, projectRoot.resolve("src/main/java/com/example/CrashController.java")));
    }

    @Test
    void deriveTestSelectorUsesPackageAndClassName() throws Exception {
        Path testPath = tempDir.resolve("src/test/java/com/example/CrashControllerTest.java");
        Files.createDirectories(testPath.getParent());
        Files.writeString(testPath, """
                package com.example;

                class CrashControllerTest {
                }
                """);

        assertEquals("com.example.CrashControllerTest", projectFileService.deriveTestSelector(testPath));
    }

    @Test
    void findNearestMavenProjectRootWalksUpFromSourceFile() throws Exception {
        Path projectRoot = tempDir.resolve("petclinic");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");
        Path cutPath = projectRoot.resolve("src/main/java/com/example/CrashController.java");
        Files.createDirectories(cutPath.getParent());
        Files.writeString(cutPath, "package com.example;");

        assertEquals(projectRoot, projectFileService.findNearestMavenProjectRoot(cutPath));
    }

    @Test
    void findNearestBuildProjectRootWalksUpFromGradleSourceFile() throws Exception {
        Path projectRoot = tempDir.resolve("gradle-sample");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("build.gradle.kts"), "plugins { java }");
        Path cutPath = projectRoot.resolve("src/main/java/com/example/CrashController.java");
        Files.createDirectories(cutPath.getParent());
        Files.writeString(cutPath, "package com.example;");

        assertEquals(projectRoot, projectFileService.findNearestBuildProjectRoot(cutPath));
        assertEquals(BuildTool.GRADLE, projectFileService.detectBuildTool(projectRoot, null).orElseThrow());
    }

    @Test
    void resolveImportedContextClassPathsIncludesDirectStaticPackageWildcardAndStaticWildcardImports() throws Exception {
        Path projectRoot = tempDir.resolve("petclinic");
        Path cutPath = projectRoot.resolve("src/main/java/com/example/CrashController.java");
        Files.createDirectories(cutPath.getParent());
        Files.writeString(cutPath, """
                package com.example;

                import com.example.support.Helper;
                import static com.example.util.Util.VALUE;
                import static com.example.constants.Constants.*;
                import com.example.wild.*;
                import java.util.List;

                public class CrashController {
                }
                """);

        writeSource(projectRoot, "src/main/java/com/example/support/Helper.java", "package com.example.support;\nclass Helper {}\n");
        writeSource(projectRoot, "src/main/java/com/example/util/Util.java", "package com.example.util;\nclass Util {}\n");
        writeSource(projectRoot, "src/main/java/com/example/constants/Constants.java", "package com.example.constants;\nclass Constants {}\n");
        writeSource(projectRoot, "src/main/java/com/example/wild/Owner.java", "package com.example.wild;\nclass Owner {}\n");
        writeSource(projectRoot, "src/main/java/com/example/wild/Pet.java", "package com.example.wild;\nclass Pet {}\n");

        assertEquals(
                List.of(
                        "com/example/support/Helper.java",
                        "com/example/util/Util.java",
                        "com/example/constants/Constants.java",
                        "com/example/wild/Owner.java",
                        "com/example/wild/Pet.java"
                ),
                projectFileService.resolveImportedContextClassPaths(projectRoot, cutPath)
        );
    }

    @Test
    void resolveImportedContextClassPathsPreservesModulePrefix() throws Exception {
        Path projectRoot = tempDir.resolve("workspace");
        Path cutPath = projectRoot.resolve("module-a/src/main/java/com/example/CrashController.java");
        writeSource(
                projectRoot,
                "module-a/src/main/java/com/example/CrashController.java",
                """
                package com.example;

                import com.example.support.Helper;

                public class CrashController {
                }
                """
        );
        writeSource(
                projectRoot,
                "module-a/src/main/java/com/example/support/Helper.java",
                "package com.example.support;\nclass Helper {}\n"
        );

        assertEquals(
                List.of("module-a/src/main/java/com/example/support/Helper.java"),
                projectFileService.resolveImportedContextClassPaths(projectRoot, cutPath)
        );
    }

    @Test
    void resolveImportedContextClassPathsIncludesDependencySourceImports() throws Exception {
        Path projectRoot = tempDir.resolve("workspace");
        Path cutPath = writeSource(
                projectRoot,
                "src/main/java/com/example/CrashController.java",
                """
                package com.example;

                import org.apache.kafka.common.utils.Utils;

                public class CrashController {
                }
                """
        );
        Path dependencyCacheRoot = tempDir.resolve("m2repo");
        writeSourceJarEntry(
                dependencyCacheRoot.resolve("org/apache/kafka/kafka-clients/3.8.0/kafka-clients-3.8.0-sources.jar"),
                "org/apache/kafka/common/utils/Utils.java",
                """
                package org.apache.kafka.common.utils;

                public class Utils {
                }
                """
        );
        ProjectFileService dependencyAwareFileService = new ProjectFileService(List.of(dependencyCacheRoot));

        assertEquals(
                List.of("org/apache/kafka/common/utils/Utils.java"),
                dependencyAwareFileService.resolveImportedContextClassPaths(projectRoot, cutPath)
        );
    }

    @Test
    void readCachedContextEntriesFormatsPathAndSource() throws Exception {
        Path projectRoot = tempDir.resolve("petclinic");
        writeSource(
                projectRoot,
                "src/main/java/com/example/support/Helper.java",
                """
                package com.example.support;

                public class Helper {
                }
                """
        );

        assertEquals(
                List.of(
                        "com/example/support/Helper.java =\npackage com.example.support;\n\npublic class Helper {\n}\n"
                ),
                projectFileService.readCachedContextEntries(projectRoot, List.of("com/example/support/Helper.java"))
        );
    }

    @Test
    void readRequestedContextSourcesPrefersTheSameModuleAsTheSourcePath() throws Exception {
        Path projectRoot = tempDir.resolve("workspace");
        Files.createDirectories(projectRoot.resolve("module-a"));
        Files.createDirectories(projectRoot.resolve("module-b"));
        Files.writeString(projectRoot.resolve("settings.gradle.kts"), "rootProject.name = \"workspace\"");
        Files.writeString(projectRoot.resolve("module-a/build.gradle.kts"), "plugins { java }");
        Files.writeString(projectRoot.resolve("module-b/build.gradle.kts"), "plugins { java }");
        Path cutPath = writeSource(
                projectRoot,
                "module-b/src/main/java/com/example/CrashController.java",
                """
                package com.example;

                public class CrashController {
                }
                """
        );
        writeSource(
                projectRoot,
                "module-a/src/main/java/com/example/RequestedContext.java",
                """
                package com.example;

                public class RequestedContext {
                    public String module() {
                        return "module-a";
                    }
                }
                """
        );
        writeSource(
                projectRoot,
                "module-b/src/main/java/com/example/RequestedContext.java",
                """
                package com.example;

                public class RequestedContext {
                    public String module() {
                        return "module-b";
                    }
                }
                """
        );

        List<String> contextSources = projectFileService.readRequestedContextSources(
                projectRoot,
                cutPath,
                List.of("com/example/RequestedContext.java")
        );

        assertEquals(1, contextSources.size());
        assertTrue(contextSources.get(0).contains("module-b"));
        assertTrue(!contextSources.get(0).contains("module-a"));
    }

    @Test
    void readRequestedContextSourcesFallsBackToDependencySources() throws Exception {
        Path projectRoot = tempDir.resolve("workspace");
        Path cutPath = writeSource(
                projectRoot,
                "src/main/java/com/example/CrashController.java",
                """
                package com.example;

                public class CrashController {
                }
                """
        );
        Path dependencyCacheRoot = tempDir.resolve("gradle-caches");
        writeSourceJarEntry(
                dependencyCacheRoot.resolve("com/google/guava/guava/33.2.1/abc123/guava-33.2.1-sources.jar"),
                "com/google/common/base/Strings.java",
                """
                package com.google.common.base;

                public final class Strings {
                }
                """
        );
        ProjectFileService dependencyAwareFileService = new ProjectFileService(List.of(dependencyCacheRoot));

        List<String> contextSources = dependencyAwareFileService.readRequestedContextSources(
                projectRoot,
                cutPath,
                List.of("com.google.common.base.Strings")
        );

        assertEquals(1, contextSources.size());
        assertTrue(contextSources.get(0).contains("package com.google.common.base;"));
        assertTrue(contextSources.get(0).contains("class Strings"));
    }

    @Test
    void inferCutPathFromTestPathRewritesConventionalTestNames() throws Exception {
        Path projectRoot = tempDir.resolve("workspace");
        writeSource(
                projectRoot,
                "src/main/java/com/example/CrashController.java",
                "package com.example;\nclass CrashController {}\n"
        );
        Path testPath = writeSource(
                projectRoot,
                "src/test/java/com/example/CrashControllerTest.java",
                "package com.example;\nclass CrashControllerTest {}\n"
        );

        assertEquals(
                projectRoot.resolve("src/main/java/com/example/CrashController.java"),
                projectFileService.inferCutPathFromTestPath(projectRoot, testPath)
        );
    }

    @Test
    void writeFileFormatsJavaSourceUsingRepoStyleIndentation() throws Exception {
        Path javaFile = tempDir.resolve("Example.java");

        projectFileService.writeFile(javaFile, "package com.example;class Example{void run(){}}");

        assertEquals(
                """
                package com.example;

                class Example {

                    void run() {
                    }
                }
                """,
                Files.readString(javaFile)
        );
    }

    private Path writeSource(Path projectRoot, String relativePath, String content) throws Exception {
        Path path = projectRoot.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }

    private void writeSourceJarEntry(Path jarPath, String entryPath, String sourceCode) throws Exception {
        Files.createDirectories(jarPath.getParent());
        try (JarOutputStream outputStream = new JarOutputStream(Files.newOutputStream(jarPath))) {
            JarEntry entry = new JarEntry(entryPath);
            outputStream.putNextEntry(entry);
            outputStream.write(sourceCode.getBytes(StandardCharsets.UTF_8));
            outputStream.closeEntry();
        }
    }
}
