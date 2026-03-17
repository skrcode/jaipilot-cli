package com.jaipilot.cli.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenReactorBootstrapperTest {

    private final MavenReactorBootstrapper bootstrapper = new MavenReactorBootstrapper();

    @TempDir
    Path tempDir;

    @Test
    void injectsJacocoAndPitForSingleModuleJunit5Project() throws IOException {
        writePom(tempDir, singleModulePom(
                """
                <dependency>
                    <groupId>org.junit.jupiter</groupId>
                    <artifactId>junit-jupiter</artifactId>
                    <version>5.11.4</version>
                    <scope>test</scope>
                </dependency>
                """
        ));
        writeJava(tempDir.resolve("src/main/java/com/example/App.java"), "package com.example; class App {}");
        writeJava(
                tempDir.resolve("src/test/java/com/example/AppTest.java"),
                "package com.example; import org.junit.jupiter.api.Test; class AppTest { @Test void ok() {} }"
        );

        MirrorBuild mirrorBuild = bootstrapper.prepare(tempDir, "0.8.13", "1.22.0");
        try {
            String mirroredPom = Files.readString(mirrorBuild.buildPomPath());
            assertTrue(mirroredPom.contains("<artifactId>jacoco-maven-plugin</artifactId>"));
            assertTrue(mirroredPom.contains("<artifactId>pitest-maven</artifactId>"));
            assertTrue(mirroredPom.contains("<artifactId>pitest-junit5-plugin</artifactId>"));
            assertTrue(mirroredPom.contains("<historyInputFile>"));
            assertTrue(mirroredPom.contains("<historyOutputFile>"));
            assertTrue(mirroredPom.contains(Path.of(System.getProperty("user.home"), ".jaipilot", "pit-history").toString()));
        } finally {
            mirrorBuild.cleanup();
        }
    }

    @Test
    void omitsPitJunit5PluginForJunit4Project() throws IOException {
        writePom(tempDir, singleModulePom(
                """
                <dependency>
                    <groupId>junit</groupId>
                    <artifactId>junit</artifactId>
                    <version>4.13.2</version>
                    <scope>test</scope>
                </dependency>
                """
        ));
        writeJava(tempDir.resolve("src/main/java/com/example/App.java"), "package com.example; class App {}");
        writeJava(
                tempDir.resolve("src/test/java/com/example/AppTest.java"),
                "package com.example; import org.junit.Test; public class AppTest { @Test public void ok() {} }"
        );

        MirrorBuild mirrorBuild = bootstrapper.prepare(tempDir, "0.8.13", "1.22.0");
        try {
            String mirroredPom = Files.readString(mirrorBuild.buildPomPath());
            assertTrue(mirroredPom.contains("<artifactId>pitest-maven</artifactId>"));
            assertFalse(mirroredPom.contains("<artifactId>pitest-junit5-plugin</artifactId>"));
        } finally {
            mirrorBuild.cleanup();
        }
    }

    @Test
    void preparesMultiModuleMirrorAndMarksAggregateCoverage() throws IOException {
        writePom(tempDir, """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>module-a</module>
                    </modules>
                </project>
                """);
        Path moduleA = tempDir.resolve("module-a");
        writePom(moduleA, singleModulePom(
                """
                <dependency>
                    <groupId>org.junit.jupiter</groupId>
                    <artifactId>junit-jupiter</artifactId>
                    <version>5.11.4</version>
                    <scope>test</scope>
                </dependency>
                """,
                """
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                </parent>
                <artifactId>module-a</artifactId>
                """
        ));
        writeJava(moduleA.resolve("src/main/java/com/example/App.java"), "package com.example; class App {}");
        writeJava(
                moduleA.resolve("src/test/java/com/example/AppTest.java"),
                "package com.example; import org.junit.jupiter.api.Test; class AppTest { @Test void ok() {} }"
        );

        MirrorBuild mirrorBuild = bootstrapper.prepare(tempDir, "0.8.13", "1.22.0");
        try {
            assertTrue(mirrorBuild.runAggregateCoverage());
            assertEquals(2, mirrorBuild.modules().size());
            String mirroredRootPom = Files.readString(mirrorBuild.buildPomPath());
            String mirroredChildPom = Files.readString(mirrorBuild.tempProjectRoot().resolve("module-a/pom.xml"));
            assertTrue(mirroredRootPom.contains("<artifactId>jacoco-maven-plugin</artifactId>"));
            assertTrue(mirroredChildPom.contains("<artifactId>pitest-maven</artifactId>"));
            assertTrue(mirroredChildPom.contains("module-a_pom.xml.bin"));
        } finally {
            mirrorBuild.cleanup();
        }
    }

    @Test
    void rejectsUnsupportedTestFrameworks() throws IOException {
        writePom(tempDir, singleModulePom(
                """
                <dependency>
                    <groupId>org.testng</groupId>
                    <artifactId>testng</artifactId>
                    <version>7.10.2</version>
                    <scope>test</scope>
                </dependency>
                """
        ));
        writeJava(tempDir.resolve("src/main/java/com/example/App.java"), "package com.example; class App {}");
        writeJava(
                tempDir.resolve("src/test/java/com/example/AppTest.java"),
                "package com.example; import org.testng.annotations.Test; public class AppTest { @Test public void ok() {} }"
        );

        BootstrapException exception = assertThrows(
                BootstrapException.class,
                () -> bootstrapper.prepare(tempDir, "0.8.13", "1.22.0")
        );
        assertTrue(exception.issue().reason().contains("Only JUnit 4 and JUnit 5 are supported"));
    }

    private void writePom(Path projectRoot, String content) throws IOException {
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("pom.xml"), content);
    }

    private void writeJava(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private String singleModulePom(String dependencies) {
        return singleModulePom(dependencies, "<artifactId>sample</artifactId>");
    }

    private String singleModulePom(String dependencies, String projectBody) {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    %s
                    <version>1.0.0</version>
                    <properties>
                        <maven.compiler.release>17</maven.compiler.release>
                    </properties>
                    <dependencies>
                        %s
                    </dependencies>
                </project>
                """.formatted(projectBody, dependencies);
    }
}
