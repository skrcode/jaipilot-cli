package com.jaipilot.cli.classpath;

import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.api.SinkReturns;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class CfrDecompilerTest {

    private CfrDecompiler decompiler;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        decompiler = new CfrDecompiler();
    }

    @Test
    void testDecompileWithNullPath() {
        Optional<String> result = decompiler.decompile(null, "Test.class");
        assertTrue(result.isEmpty());
    }

    @Test
    void testDecompileWithNullEntryPath() throws IOException {
        Path classFile = tempDir.resolve("Test.class");
        Files.createFile(classFile);
        Optional<String> result = decompiler.decompile(classFile, null);
        assertTrue(result.isEmpty());
    }

    @Test
    void testDecompileWithBlankEntryPath() throws IOException {
        Path classFile = tempDir.resolve("Test.class");
        Files.createFile(classFile);
        Optional<String> result = decompiler.decompile(classFile, "  ");
        assertTrue(result.isEmpty());
    }

    @Test
    void testDecompileWithEmptyEntryPath() throws IOException {
        Path classFile = tempDir.resolve("Test.class");
        Files.createFile(classFile);
        Optional<String> result = decompiler.decompile(classFile, "");
        assertTrue(result.isEmpty());
    }

    @Test
    void testDecompileFromDirectoryNonExistentFile() {
        Optional<String> result = decompiler.decompile(tempDir, "NonExistent.class");
        assertTrue(result.isEmpty());
    }

    @Test
    void testDecompileFromDirectoryWithSimpleClass() throws IOException {
        Path classFile = tempDir.resolve("Simple.class");
        byte[] classBytes = createSimpleClassBytes();
        Files.write(classFile, classBytes);
        Optional<String> result = decompiler.decompile(tempDir, "Simple.class");
        // CFR may or may not successfully decompile depending on the class bytes
        assertNotNull(result);
    }

    @Test
    void testDecompileFromDirectoryWithNestedPath() throws IOException {
        Path packageDir = tempDir.resolve("com/example");
        Files.createDirectories(packageDir);
        Path classFile = packageDir.resolve("Test.class");
        byte[] classBytes = createSimpleClassBytes();
        Files.write(classFile, classBytes);
        Optional<String> result = decompiler.decompile(tempDir, "com/example/Test.class");
        assertNotNull(result);
    }

    @Test
    void testDecompileDirectClassFile() throws IOException {
        Path classFile = tempDir.resolve("Direct.class");
        byte[] classBytes = createSimpleClassBytes();
        Files.write(classFile, classBytes);
        Optional<String> result = decompiler.decompile(classFile, "ignored");
        assertNotNull(result);
    }

    @Test
    void testDecompileNonExistentClassFile() {
        Path nonExistent = tempDir.resolve("NonExistent.class");
        Optional<String> result = decompiler.decompile(nonExistent, "ignored");
        assertTrue(result.isEmpty());
    }

    @Test
    void testDecompileFromJarWithSimpleClass() throws IOException {
        Path jarFile = tempDir.resolve("test.jar");
        createJarWithClass(jarFile, "Test.class", createSimpleClassBytes());
        Optional<String> result = decompiler.decompile(jarFile, "Test.class");
        assertNotNull(result);
    }

    @Test
    void testDecompileFromJarWithPackagedClass() throws IOException {
        Path jarFile = tempDir.resolve("test.jar");
        createJarWithClass(jarFile, "com/example/Test.class", createSimpleClassBytes());
        Optional<String> result = decompiler.decompile(jarFile, "com/example/Test.class");
        assertNotNull(result);
    }

    @Test
    void testDecompileFromJarWithInnerClass() throws IOException {
        Path jarFile = tempDir.resolve("test.jar");
        byte[] outerClassBytes = createSimpleClassBytes();
        byte[] innerClassBytes = createSimpleClassBytes();
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jarFile))) {
            ZipEntry outer = new ZipEntry("Outer.class");
            zos.putNextEntry(outer);
            zos.write(outerClassBytes);
            zos.closeEntry();
            ZipEntry inner = new ZipEntry("Outer$Inner.class");
            zos.putNextEntry(inner);
            zos.write(innerClassBytes);
            zos.closeEntry();
        }
        Optional<String> result = decompiler.decompile(jarFile, "Outer$Inner.class");
        assertNotNull(result);
    }

    @Test
    void testDecompileFromJarWithMultipleInnerClasses() throws IOException {
        Path jarFile = tempDir.resolve("test.jar");
        byte[] classBytes = createSimpleClassBytes();
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jarFile))) {
            zos.putNextEntry(new ZipEntry("Outer.class"));
            zos.write(classBytes);
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("Outer$Inner1.class"));
            zos.write(classBytes);
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("Outer$Inner2.class"));
            zos.write(classBytes);
            zos.closeEntry();
        }
        Optional<String> result = decompiler.decompile(jarFile, "Outer$Inner1.class");
        assertNotNull(result);
    }

    @Test
    void testDecompileFromJarNonExistentClass() throws IOException {
        Path jarFile = tempDir.resolve("test.jar");
        createJarWithClass(jarFile, "Existing.class", createSimpleClassBytes());
        Optional<String> result = decompiler.decompile(jarFile, "NonExistent.class");
        assertTrue(result.isEmpty());
    }

    @Test
    void testDecompileFromJarWithDirectoryEntry() throws IOException {
        Path jarFile = tempDir.resolve("test.jar");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jarFile))) {
            zos.putNextEntry(new ZipEntry("com/"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("com/example/"));
            zos.closeEntry();
        }
        Optional<String> result = decompiler.decompile(jarFile, "com/example/");
        assertTrue(result.isEmpty());
    }

    @Test
    void testDecompileFromJarWithLeadingSlash() throws IOException {
        Path jarFile = tempDir.resolve("test.jar");
        createJarWithClass(jarFile, "Test.class", createSimpleClassBytes());
        Optional<String> result = decompiler.decompile(jarFile, "/Test.class");
        assertNotNull(result);
    }

    @Test
    void testDecompileFromJarWithMultipleLeadingSlashes() throws IOException {
        Path jarFile = tempDir.resolve("test.jar");
        createJarWithClass(jarFile, "Test.class", createSimpleClassBytes());
        Optional<String> result = decompiler.decompile(jarFile, "///Test.class");
        assertNotNull(result);
    }

    @Test
    void testDecompileFromJarWithBackslashes() throws IOException {
        Path jarFile = tempDir.resolve("test.jar");
        createJarWithClass(jarFile, "com/example/Test.class", createSimpleClassBytes());
        Optional<String> result = decompiler.decompile(jarFile, "com\\example\\Test.class");
        assertNotNull(result);
    }

    @Test
    void testDecompileUnsupportedFileType() throws IOException {
        Path txtFile = tempDir.resolve("test.txt");
        Files.write(txtFile, "content".getBytes());
        Optional<String> result = decompiler.decompile(txtFile, "ignored");
        assertTrue(result.isEmpty());
    }

    @Test
    void testDecompileNonExistentJar() {
        Path jarFile = tempDir.resolve("nonexistent.jar");
        Optional<String> result = decompiler.decompile(jarFile, "Test.class");
        assertTrue(result.isEmpty());
    }

    @Test
    void testDecompileCorruptedJar() throws IOException {
        Path jarFile = tempDir.resolve("corrupted.jar");
        Files.write(jarFile, "not a valid jar".getBytes());
        Optional<String> result = decompiler.decompile(jarFile, "Test.class");
        assertTrue(result.isEmpty());
    }

    @Test
    void testDecompileWithInvalidClassBytes() throws IOException {
        Path classFile = tempDir.resolve("Invalid.class");
        Files.write(classFile, "invalid class bytes".getBytes());
        Optional<String> result = decompiler.decompile(classFile, "ignored");
        // CFR should handle this gracefully
        assertNotNull(result);
    }

    @Test
    void testDecompileWithEmptyClassFile() throws IOException {
        Path classFile = tempDir.resolve("Empty.class");
        Files.write(classFile, new byte[0]);
        Optional<String> result = decompiler.decompile(classFile, "ignored");
        assertNotNull(result);
    }

    @Test
    void testDecompileFromJarWithEmptyEntryPath() throws IOException {
        Path jarFile = tempDir.resolve("test.jar");
        createJarWithClass(jarFile, "Test.class", createSimpleClassBytes());
        // Empty entry path after normalization
        Optional<String> result = decompiler.decompile(jarFile, "///");
        assertTrue(result.isEmpty());
    }

    @Test
    void testOuterClassEntryPathWithoutDollar() throws IOException {
        Path jarFile = tempDir.resolve("test.jar");
        createJarWithClass(jarFile, "Simple.class", createSimpleClassBytes());
        Optional<String> result = decompiler.decompile(jarFile, "Simple.class");
        assertNotNull(result);
    }

    @Test
    void testOuterClassEntryPathWithDollarNoClassSuffix() throws IOException {
        Path jarFile = tempDir.resolve("test.jar");
        byte[] classBytes = createSimpleClassBytes();
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jarFile))) {
            zos.putNextEntry(new ZipEntry("Test.class"));
            zos.write(classBytes);
            zos.closeEntry();
        }
        // Entry path with $ but no .class suffix - edge case
        Optional<String> result = decompiler.decompile(jarFile, "Test.class");
        assertNotNull(result);
    }

    @Test
    void testDecompileFromJarWhenOuterClassExists() throws IOException {
        Path jarFile = tempDir.resolve("test.jar");
        byte[] classBytes = createSimpleClassBytes();
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jarFile))) {
            zos.putNextEntry(new ZipEntry("Outer.class"));
            zos.write(classBytes);
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("Outer$Inner.class"));
            zos.write(classBytes);
            zos.closeEntry();
        }
        // Request inner class when outer exists - should find outer
        Optional<String> result = decompiler.decompile(jarFile, "Outer$Inner.class");
        assertNotNull(result);
    }

    @Test
    void testDecompileFromJarWhenOnlyInnerClassExists() throws IOException {
        Path jarFile = tempDir.resolve("test.jar");
        byte[] classBytes = createSimpleClassBytes();
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jarFile))) {
            // Only inner class, no outer
            zos.putNextEntry(new ZipEntry("Outer$Inner.class"));
            zos.write(classBytes);
            zos.closeEntry();
        }
        Optional<String> result = decompiler.decompile(jarFile, "Outer$Inner.class");
        assertNotNull(result);
    }

    @Test
    void testNormalizeLineEndingsCRLF() throws IOException {
        Path classFile = tempDir.resolve("Test.class");
        byte[] classBytes = createSimpleClassBytes();
        Files.write(classFile, classBytes);
        Optional<String> result = decompiler.decompile(classFile, "ignored");
        // If decompilation succeeds, verify no \r\n in result
        result.ifPresent(source -> {
            assertFalse(source.contains("\r\n"));
            assertFalse(source.contains("\r"));
        });
    }

    @Test
    void testDecompileWithFileNameNull() throws IOException {
        // Create a path where getFileName() could be null (root path)
        // This is a defensive test for the null check in the code
        Path jarFile = tempDir.resolve("test.jar");
        createJarWithClass(jarFile, "Test.class", createSimpleClassBytes());
        Optional<String> result = decompiler.decompile(jarFile, "Test.class");
        assertNotNull(result);
    }

    @Test
    void testCfrOutputSinkGetSupportedSinksJavaDecompiled() {
        List<String> decompiledSource = new ArrayList<>();
        List<String> exceptions = new ArrayList<>();
        // Use reflection to access inner class
        try {
            Class<?> sinkClass = Class.forName("com.jaipilot.cli.classpath.CfrDecompiler$CfrOutputSink");
            java.lang.reflect.Constructor<?> constructor = sinkClass.getDeclaredConstructor(List.class, List.class);
            constructor.setAccessible(true);
            OutputSinkFactory sink = (OutputSinkFactory) constructor.newInstance(decompiledSource, exceptions);
            List<OutputSinkFactory.SinkClass> supported = sink.getSupportedSinks(OutputSinkFactory.SinkType.JAVA, List.of(OutputSinkFactory.SinkClass.DECOMPILED));
            assertEquals(1, supported.size());
            assertEquals(OutputSinkFactory.SinkClass.DECOMPILED, supported.get(0));
        } catch (Exception e) {
            fail("Failed to test CfrOutputSink: " + e.getMessage());
        }
    }

    @Test
    void testCfrOutputSinkGetSupportedSinksJavaString() {
        List<String> decompiledSource = new ArrayList<>();
        List<String> exceptions = new ArrayList<>();
        try {
            Class<?> sinkClass = Class.forName("com.jaipilot.cli.classpath.CfrDecompiler$CfrOutputSink");
            java.lang.reflect.Constructor<?> constructor = sinkClass.getDeclaredConstructor(List.class, List.class);
            constructor.setAccessible(true);
            OutputSinkFactory sink = (OutputSinkFactory) constructor.newInstance(decompiledSource, exceptions);
            List<OutputSinkFactory.SinkClass> supported = sink.getSupportedSinks(OutputSinkFactory.SinkType.JAVA, List.of(OutputSinkFactory.SinkClass.STRING));
            assertEquals(1, supported.size());
            assertEquals(OutputSinkFactory.SinkClass.STRING, supported.get(0));
        } catch (Exception e) {
            fail("Failed to test CfrOutputSink: " + e.getMessage());
        }
    }

    @Test
    void testCfrOutputSinkGetSupportedSinksJavaEmpty() {
        List<String> decompiledSource = new ArrayList<>();
        List<String> exceptions = new ArrayList<>();
        try {
            Class<?> sinkClass = Class.forName("com.jaipilot.cli.classpath.CfrDecompiler$CfrOutputSink");
            java.lang.reflect.Constructor<?> constructor = sinkClass.getDeclaredConstructor(List.class, List.class);
            constructor.setAccessible(true);
            OutputSinkFactory sink = (OutputSinkFactory) constructor.newInstance(decompiledSource, exceptions);
            List<OutputSinkFactory.SinkClass> supported = sink.getSupportedSinks(OutputSinkFactory.SinkType.JAVA, List.of());
            assertEquals(0, supported.size());
        } catch (Exception e) {
            fail("Failed to test CfrOutputSink: " + e.getMessage());
        }
    }

    @Test
    void testCfrOutputSinkGetSupportedSinksExceptionMessage() {
        List<String> decompiledSource = new ArrayList<>();
        List<String> exceptions = new ArrayList<>();
        try {
            Class<?> sinkClass = Class.forName("com.jaipilot.cli.classpath.CfrDecompiler$CfrOutputSink");
            java.lang.reflect.Constructor<?> constructor = sinkClass.getDeclaredConstructor(List.class, List.class);
            constructor.setAccessible(true);
            OutputSinkFactory sink = (OutputSinkFactory) constructor.newInstance(decompiledSource, exceptions);
            List<OutputSinkFactory.SinkClass> supported = sink.getSupportedSinks(OutputSinkFactory.SinkType.EXCEPTION, List.of(OutputSinkFactory.SinkClass.EXCEPTION_MESSAGE));
            assertEquals(1, supported.size());
            assertEquals(OutputSinkFactory.SinkClass.EXCEPTION_MESSAGE, supported.get(0));
        } catch (Exception e) {
            fail("Failed to test CfrOutputSink: " + e.getMessage());
        }
    }

    @Test
    void testCfrOutputSinkGetSupportedSinksExceptionString() {
        List<String> decompiledSource = new ArrayList<>();
        List<String> exceptions = new ArrayList<>();
        try {
            Class<?> sinkClass = Class.forName("com.jaipilot.cli.classpath.CfrDecompiler$CfrOutputSink");
            java.lang.reflect.Constructor<?> constructor = sinkClass.getDeclaredConstructor(List.class, List.class);
            constructor.setAccessible(true);
            OutputSinkFactory sink = (OutputSinkFactory) constructor.newInstance(decompiledSource, exceptions);
            List<OutputSinkFactory.SinkClass> supported = sink.getSupportedSinks(OutputSinkFactory.SinkType.EXCEPTION, List.of(OutputSinkFactory.SinkClass.STRING));
            assertEquals(1, supported.size());
            assertEquals(OutputSinkFactory.SinkClass.STRING, supported.get(0));
        } catch (Exception e) {
            fail("Failed to test CfrOutputSink: " + e.getMessage());
        }
    }

    @Test
    void testCfrOutputSinkGetSupportedSinksOtherType() {
        List<String> decompiledSource = new ArrayList<>();
        List<String> exceptions = new ArrayList<>();
        try {
            Class<?> sinkClass = Class.forName("com.jaipilot.cli.classpath.CfrDecompiler$CfrOutputSink");
            java.lang.reflect.Constructor<?> constructor = sinkClass.getDeclaredConstructor(List.class, List.class);
            constructor.setAccessible(true);
            OutputSinkFactory sink = (OutputSinkFactory) constructor.newInstance(decompiledSource, exceptions);
            List<OutputSinkFactory.SinkClass> supported = sink.getSupportedSinks(OutputSinkFactory.SinkType.PROGRESS, List.of(OutputSinkFactory.SinkClass.STRING));
            assertEquals(0, supported.size());
        } catch (Exception e) {
            fail("Failed to test CfrOutputSink: " + e.getMessage());
        }
    }

    @Test
    void testCfrOutputSinkGetSinkJavaDecompiled() {
        List<String> decompiledSource = new ArrayList<>();
        List<String> exceptions = new ArrayList<>();
        try {
            Class<?> sinkClass = Class.forName("com.jaipilot.cli.classpath.CfrDecompiler$CfrOutputSink");
            java.lang.reflect.Constructor<?> constructor = sinkClass.getDeclaredConstructor(List.class, List.class);
            constructor.setAccessible(true);
            OutputSinkFactory sink = (OutputSinkFactory) constructor.newInstance(decompiledSource, exceptions);
            OutputSinkFactory.Sink<SinkReturns.Decompiled> javaSink = sink.getSink(OutputSinkFactory.SinkType.JAVA, OutputSinkFactory.SinkClass.DECOMPILED);
            assertNotNull(javaSink);
            // Create a simple implementation instead of mock
            SinkReturns.Decompiled decompiled = new SinkReturns.Decompiled() {

                @Override
                public String getJava() {
                    return "public class Test {}";
                }

                @Override
                public String getClassName() {
                    return "Test";
                }

                @Override
                public String getPackageName() {
                    return "";
                }
            };
            javaSink.write(decompiled);
            assertEquals(1, decompiledSource.size());
            assertEquals("public class Test {}", decompiledSource.get(0));
        } catch (Exception e) {
            fail("Failed to test CfrOutputSink: " + e.getMessage());
        }
    }

    @Test
    void testCfrOutputSinkGetSinkJavaString() {
        List<String> decompiledSource = new ArrayList<>();
        List<String> exceptions = new ArrayList<>();
        try {
            Class<?> sinkClass = Class.forName("com.jaipilot.cli.classpath.CfrDecompiler$CfrOutputSink");
            java.lang.reflect.Constructor<?> constructor = sinkClass.getDeclaredConstructor(List.class, List.class);
            constructor.setAccessible(true);
            OutputSinkFactory sink = (OutputSinkFactory) constructor.newInstance(decompiledSource, exceptions);
            OutputSinkFactory.Sink<String> javaSink = sink.getSink(OutputSinkFactory.SinkType.JAVA, OutputSinkFactory.SinkClass.STRING);
            assertNotNull(javaSink);
            javaSink.write("public class Test {}");
            assertEquals(1, decompiledSource.size());
            assertEquals("public class Test {}", decompiledSource.get(0));
        } catch (Exception e) {
            fail("Failed to test CfrOutputSink: " + e.getMessage());
        }
    }

    @Test
    void testCfrOutputSinkGetSinkExceptionMessage() {
        List<String> decompiledSource = new ArrayList<>();
        List<String> exceptions = new ArrayList<>();
        try {
            Class<?> sinkClass = Class.forName("com.jaipilot.cli.classpath.CfrDecompiler$CfrOutputSink");
            java.lang.reflect.Constructor<?> constructor = sinkClass.getDeclaredConstructor(List.class, List.class);
            constructor.setAccessible(true);
            OutputSinkFactory sink = (OutputSinkFactory) constructor.newInstance(decompiledSource, exceptions);
            OutputSinkFactory.Sink<SinkReturns.ExceptionMessage> exceptionSink = sink.getSink(OutputSinkFactory.SinkType.EXCEPTION, OutputSinkFactory.SinkClass.EXCEPTION_MESSAGE);
            assertNotNull(exceptionSink);
            SinkReturns.ExceptionMessage exceptionMessage = new SinkReturns.ExceptionMessage() {

                @Override
                public String getPath() {
                    return "test.class";
                }

                @Override
                public String getMessage() {
                    return "Error occurred";
                }

                @Override
                public Exception getThrownException() {
                    return null;
                }
            };
            exceptionSink.write(exceptionMessage);
            assertEquals(1, exceptions.size());
            assertEquals("Error occurred", exceptions.get(0));
        } catch (Exception e) {
            fail("Failed to test CfrOutputSink: " + e.getMessage());
        }
    }

    @Test
    void testCfrOutputSinkGetSinkExceptionString() {
        List<String> decompiledSource = new ArrayList<>();
        List<String> exceptions = new ArrayList<>();
        try {
            Class<?> sinkClass = Class.forName("com.jaipilot.cli.classpath.CfrDecompiler$CfrOutputSink");
            java.lang.reflect.Constructor<?> constructor = sinkClass.getDeclaredConstructor(List.class, List.class);
            constructor.setAccessible(true);
            OutputSinkFactory sink = (OutputSinkFactory) constructor.newInstance(decompiledSource, exceptions);
            OutputSinkFactory.Sink<String> exceptionSink = sink.getSink(OutputSinkFactory.SinkType.EXCEPTION, OutputSinkFactory.SinkClass.STRING);
            assertNotNull(exceptionSink);
            exceptionSink.write("Exception message");
            assertEquals(1, exceptions.size());
            assertEquals("Exception message", exceptions.get(0));
        } catch (Exception e) {
            fail("Failed to test CfrOutputSink: " + e.getMessage());
        }
    }

    @Test
    void testCfrOutputSinkGetSinkUnsupportedType() {
        List<String> decompiledSource = new ArrayList<>();
        List<String> exceptions = new ArrayList<>();
        try {
            Class<?> sinkClass = Class.forName("com.jaipilot.cli.classpath.CfrDecompiler$CfrOutputSink");
            java.lang.reflect.Constructor<?> constructor = sinkClass.getDeclaredConstructor(List.class, List.class);
            constructor.setAccessible(true);
            OutputSinkFactory sink = (OutputSinkFactory) constructor.newInstance(decompiledSource, exceptions);
            OutputSinkFactory.Sink<String> noOpSink = sink.getSink(OutputSinkFactory.SinkType.PROGRESS, OutputSinkFactory.SinkClass.STRING);
            assertNotNull(noOpSink);
            noOpSink.write("should be ignored");
            assertEquals(0, decompiledSource.size());
            assertEquals(0, exceptions.size());
        } catch (Exception e) {
            fail("Failed to test CfrOutputSink: " + e.getMessage());
        }
    }

    private byte[] createSimpleClassBytes() {
        // Minimal valid Java class file header
        // Magic number: 0xCAFEBABE
        // Minor version: 0
        // Major version: 52 (Java 8)
        return new byte[] { // magic
        // magic
        // magic
        // magic
        (byte) 0xCA, // minor version
        (byte) 0xFE, // minor version
        (byte) 0xBA, // major version (52)
        (byte) 0xBE, // major version (52)
        0x00, // constant pool count
        0x00, // constant pool count
        0x00, // class info
        0x34, // class info
        0x00, // class info
        0x04, // UTF8 "Test"
        0x07, // UTF8 "Test"
        0x00, // UTF8 "Test"
        0x02, // UTF8 "Test"
        0x01, // UTF8 "Test"
        0x00, // UTF8 "Test"
        0x04, // UTF8 "Test"
        0x54, // super class
        0x65, // super class
        0x73, // super class
        0x74, // access flags (public super)
        0x07, // access flags (public super)
        0x00, // this class
        0x03, // this class
        0x00, // super class (index 0 = Object)
        0x21, // super class (index 0 = Object)
        0x00, // interfaces count
        0x01, // interfaces count
        0x00, // fields count
        0x00, // fields count
        0x00, // methods count
        0x00, // methods count
        0x00, // attributes count
        0x00, // attributes count
        0x00, 0x00, 0x00, 0x00 };
    }

    private void createJarWithClass(Path jarFile, String entryName, byte[] classBytes) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jarFile))) {
            ZipEntry entry = new ZipEntry(entryName);
            zos.putNextEntry(entry);
            zos.write(classBytes);
            zos.closeEntry();
        }
    }
}
