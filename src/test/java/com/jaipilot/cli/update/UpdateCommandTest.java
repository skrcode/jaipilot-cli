package com.jaipilot.cli.update;

import com.jaipilot.cli.commands.UpdateCommand;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateCommandTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setConfigHome() {
        System.setProperty("jaipilot.config.home", tempDir.resolve("config").toString());
    }

    @AfterEach
    void clearConfigHome() {
        System.clearProperty("jaipilot.config.home");
    }

    @Test
    void checkReportsLatestVersionAndExitCodeWhenUpdateIsAvailable() {
        StringWriter outBuffer = new StringWriter();
        UpdateService updateService = new UpdateService(
                new StubReleaseClient("999.0.0"),
                new UpdateSettingsStore(),
                fixedClock(),
                Optional::<InstallationLayout>empty
        );

        int exitCode = new CommandLine(new UpdateCommand(updateService))
                .setOut(new PrintWriter(outBuffer, true))
                .setErr(new PrintWriter(new StringWriter(), true))
                .execute("--check");

        assertEquals(1, exitCode);
        assertTrue(outBuffer.toString().contains("Latest version: 999.0.0"));
        assertTrue(outBuffer.toString().contains("Status: update available"));
    }

    @Test
    void enableAutoUpdatesPersistsSettingWithoutInstalling() {
        StringWriter outBuffer = new StringWriter();
        UpdateService updateService = new UpdateService(
                new StubReleaseClient("0.2.0"),
                new UpdateSettingsStore(),
                fixedClock(),
                Optional::<InstallationLayout>empty
        );

        int exitCode = new CommandLine(new UpdateCommand(updateService))
                .setOut(new PrintWriter(outBuffer, true))
                .setErr(new PrintWriter(new StringWriter(), true))
                .execute("--enable-auto-updates");

        assertEquals(0, exitCode);
        assertTrue(outBuffer.toString().contains("Automatic startup updates: enabled"));
        assertTrue(outBuffer.toString().contains("Managed install: no"));
        assertTrue(updateService.autoUpdatesEnabled());
    }

    @Test
    void installFailsCleanlyWithoutManagedInstall() {
        StringWriter outBuffer = new StringWriter();
        UpdateService updateService = new UpdateService(
                new StubReleaseClient("0.2.0"),
                new UpdateSettingsStore(),
                fixedClock(),
                Optional::<InstallationLayout>empty
        );

        int exitCode = new CommandLine(new UpdateCommand(updateService))
                .setOut(new PrintWriter(outBuffer, true))
                .setErr(new PrintWriter(new StringWriter(), true))
                .execute();

        assertEquals(CommandLine.ExitCode.SOFTWARE, exitCode);
        assertTrue(outBuffer.toString().contains("Self-update is only available"));
    }

    private Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-04-01T00:00:00Z"), ZoneOffset.UTC);
    }

    private static final class StubReleaseClient implements ReleaseClient {

        private final String latestVersion;

        private StubReleaseClient(String latestVersion) {
            this.latestVersion = latestVersion;
        }

        @Override
        public String fetchLatestVersion(java.time.Duration timeout) {
            return latestVersion;
        }

        @Override
        public java.net.URI archiveUri(String version, String platform) {
            return Path.of("unused.tar.gz").toUri();
        }

        @Override
        public java.net.URI checksumUri(String version, String platform) {
            return Path.of("unused.tar.gz.sha256").toUri();
        }

        @Override
        public void download(java.net.URI source, Path destination, java.time.Duration timeout) {
            throw new UnsupportedOperationException("download should not be called in this test");
        }
    }
}
