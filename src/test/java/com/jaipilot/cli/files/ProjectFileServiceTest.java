package com.jaipilot.cli.files;

import com.jaipilot.cli.process.BuildTool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
}
