package com.jaipilot.cli.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void installVersionMigratesLegacyInstallIntoVersionedLayout() throws Exception {
        String targetVersion = "0.2.0";
        ReleaseAssets releaseAssets = createReleaseAssets(targetVersion);
        Path legacyAppRoot = createLegacyInstallRoot();

        UpdateService updateService = new UpdateService(
                new FakeReleaseClient(targetVersion, releaseAssets.archiveUri(), releaseAssets.checksumUri()),
                new UpdateSettingsStore(tempDir.resolve("config/update-settings.json")),
                fixedClock(),
                () -> InstallationLayout.detect(legacyAppRoot.resolve("lib").resolve("jaipilot.jar"))
        );

        UpdateInstallResult result = updateService.installVersion(targetVersion, java.time.Duration.ofSeconds(5));

        assertTrue(result.changed());
        assertTrue(result.migratedLegacyLayout());
        assertEquals(targetVersion, result.installedVersion());
        assertTrue(Files.isRegularFile(legacyAppRoot.resolve("versions").resolve(targetVersion).resolve("lib").resolve("jaipilot.jar")));
        assertEquals(
                Path.of("versions", targetVersion),
                Files.readSymbolicLink(legacyAppRoot.resolve("current"))
        );
        assertTrue(Files.readString(legacyAppRoot.resolve("bin").resolve("jaipilot")).contains("current/bin/jaipilot"));
    }

    @Test
    void installVersionIsNoOpWhenAlreadyCurrentOnVersionedLayout() throws Exception {
        Path appRoot = tempDir.resolve("app");
        Path versionRoot = appRoot.resolve("versions").resolve("0.1.2");
        createPayload(versionRoot);

        UpdateService updateService = new UpdateService(
                new FakeReleaseClient("0.1.2", tempDir.resolve("unused.tar.gz").toUri(), tempDir.resolve("unused.tar.gz.sha256").toUri()),
                new UpdateSettingsStore(tempDir.resolve("config/update-settings.json")),
                fixedClock(),
                () -> InstallationLayout.detect(versionRoot.resolve("lib").resolve("jaipilot.jar"))
        );

        UpdateInstallResult result = updateService.installVersion("0.1.2", java.time.Duration.ofSeconds(1));

        assertFalse(result.changed());
        assertFalse(result.migratedLegacyLayout());
        assertEquals("0.1.2", result.previousVersion());
        assertEquals("0.1.2", result.installedVersion());
    }

    @Test
    void checkForUpdatesPersistsLastCheckTimestamp() {
        Path settingsPath = tempDir.resolve("config/update-settings.json");
        UpdateSettingsStore settingsStore = new UpdateSettingsStore(settingsPath);
        UpdateService updateService = new UpdateService(
                new FakeReleaseClient("999.0.0", tempDir.resolve("archive.tar.gz").toUri(), tempDir.resolve("archive.tar.gz.sha256").toUri()),
                settingsStore,
                fixedClock(),
                Optional::<InstallationLayout>empty
        );

        UpdateCheckResult result = updateService.checkForUpdates(java.time.Duration.ofSeconds(1));

        assertTrue(result.updateAvailable());
        assertFalse(result.managedInstall());
        assertNotNull(settingsStore.load().lastCheckEpochSecond());
    }

    private ReleaseAssets createReleaseAssets(String version) throws Exception {
        Path payloadRoot = tempDir.resolve("release-src").resolve("jaipilot-" + version + "-" + platform());
        createPayload(payloadRoot);

        Path archive = tempDir.resolve("jaipilot-" + version + "-" + platform() + ".tar.gz");
        runCommand(List.of(
                "tar",
                "-czf",
                archive.toString(),
                "-C",
                payloadRoot.getParent().toString(),
                payloadRoot.getFileName().toString()
        ));

        String sha256 = sha256(archive);
        Path checksum = tempDir.resolve(archive.getFileName().toString() + ".sha256");
        Files.writeString(
                checksum,
                sha256 + "  " + archive.getFileName() + System.lineSeparator(),
                StandardCharsets.UTF_8
        );
        return new ReleaseAssets(archive.toUri(), checksum.toUri());
    }

    private Path createLegacyInstallRoot() throws IOException {
        Path appRoot = tempDir.resolve("legacy-app");
        createPayload(appRoot);
        return appRoot;
    }

    private void createPayload(Path root) throws IOException {
        Path binDir = root.resolve("bin");
        Path libDir = root.resolve("lib");
        Path runtimeBinDir = root.resolve("runtime").resolve("bin");
        Files.createDirectories(binDir);
        Files.createDirectories(libDir);
        Files.createDirectories(runtimeBinDir);

        Path launcher = binDir.resolve("jaipilot");
        Files.writeString(launcher, "#!/usr/bin/env sh\nexit 0\n", StandardCharsets.UTF_8);
        Path runtimeJava = runtimeBinDir.resolve("java");
        Files.writeString(runtimeJava, "#!/usr/bin/env sh\nexit 0\n", StandardCharsets.UTF_8);
        Files.writeString(libDir.resolve("jaipilot.jar"), "placeholder", StandardCharsets.UTF_8);

        if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwxr-xr-x"));
            Files.setPosixFilePermissions(runtimeJava, PosixFilePermissions.fromString("rwxr-xr-x"));
        } else {
            launcher.toFile().setExecutable(true, false);
            runtimeJava.toFile().setExecutable(true, false);
        }
    }

    private String platform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        String osPart = os.contains("mac") ? "macos" : "linux";
        String archPart = ("x86_64".equals(arch) || "amd64".equals(arch)) ? "x64" : "aarch64";
        return osPart + "-" + archPart;
    }

    private Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-04-01T00:00:00Z"), ZoneOffset.UTC);
    }

    private void runCommand(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }

    private String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
    }

    private record ReleaseAssets(java.net.URI archiveUri, java.net.URI checksumUri) {
    }

    private static final class FakeReleaseClient implements ReleaseClient {

        private final String latestVersion;
        private final java.net.URI archiveUri;
        private final java.net.URI checksumUri;

        private FakeReleaseClient(String latestVersion, java.net.URI archiveUri, java.net.URI checksumUri) {
            this.latestVersion = latestVersion;
            this.archiveUri = archiveUri;
            this.checksumUri = checksumUri;
        }

        @Override
        public String fetchLatestVersion(java.time.Duration timeout) {
            return latestVersion;
        }

        @Override
        public java.net.URI archiveUri(String version, String platform) {
            return archiveUri;
        }

        @Override
        public java.net.URI checksumUri(String version, String platform) {
            return checksumUri;
        }

        @Override
        public void download(java.net.URI source, Path destination, java.time.Duration timeout) {
            try {
                Files.copy(Path.of(source), destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }
}
