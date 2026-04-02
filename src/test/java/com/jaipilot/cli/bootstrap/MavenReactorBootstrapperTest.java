package com.jaipilot.cli.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void injectsJacocoForSingleModuleProject() throws IOException {
        writePom(tempDir, singleModulePom());
        writeJava(tempDir.resolve("src/main/java/com/example/App.java"), "package com.example; class App {}\n");

        MirrorBuild mirrorBuild = bootstrapper.prepare(tempDir, "0.8.13");
        try {
            String mirroredPom = Files.readString(mirrorBuild.buildPomPath());
            assertTrue(mirroredPom.contains("<artifactId>jacoco-maven-plugin</artifactId>"));
        } finally {
            mirrorBuild.cleanup();
        }
    }

    @Test
    void preparesMultiModuleMirrorAndMarksAggregateCoverage() throws IOException {
        writePom(tempDir, """
                <project xmlns=\"http://maven.apache.org/POM/4.0.0\"
                         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"
                         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">
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
        writePom(moduleA, singleModulePom("""
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                </parent>
                <artifactId>module-a</artifactId>
                """));
        writeJava(moduleA.resolve("src/main/java/com/example/App.java"), "package com.example; class App {}\n");

        MirrorBuild mirrorBuild = bootstrapper.prepare(tempDir, "0.8.13");
        try {
            assertTrue(mirrorBuild.runAggregateCoverage());
            assertEquals(2, mirrorBuild.modules().size());
            String mirroredRootPom = Files.readString(mirrorBuild.buildPomPath());
            String mirroredChildPom = Files.readString(mirrorBuild.tempProjectRoot().resolve("module-a/pom.xml"));
            assertTrue(mirroredRootPom.contains("<artifactId>jacoco-maven-plugin</artifactId>"));
            assertTrue(mirroredChildPom.contains("<artifactId>jacoco-maven-plugin</artifactId>"));
        } finally {
            mirrorBuild.cleanup();
        }
    }

    @Test
    void rejectsMissingModulePom() throws IOException {
        writePom(tempDir, """
                <project xmlns=\"http://maven.apache.org/POM/4.0.0\"
                         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"
                         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>missing-module</module>
                    </modules>
                </project>
                """);

        BootstrapException exception = assertThrows(
                BootstrapException.class,
                () -> bootstrapper.prepare(tempDir, "0.8.13")
        );
        assertTrue(exception.issue().reason().contains("Module pom.xml could not be found"));
    }

    private void writePom(Path projectRoot, String content) throws IOException {
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("pom.xml"), content);
    }

    private void writeJava(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private String singleModulePom() {
        return singleModulePom("<artifactId>sample</artifactId>");
    }

    private String singleModulePom(String projectBody) {
        return """
                <project xmlns=\"http://maven.apache.org/POM/4.0.0\"
                         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"
                         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    %s
                    <version>1.0.0</version>
                </project>
                """.formatted(projectBody);
    }
}
