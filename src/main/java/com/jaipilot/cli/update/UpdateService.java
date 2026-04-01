package com.jaipilot.cli.update;

import com.jaipilot.cli.JaiPilotVersionProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

public final class UpdateService {

    private static final long STARTUP_CHECK_INTERVAL_SECONDS = Duration.ofHours(24).toSeconds();
    private static final boolean DEFAULT_AUTO_UPDATES = false;
    private static final Set<PosixFilePermission> EXECUTABLE_FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rwxr-xr-x");

    private final ReleaseClient releaseClient;
    private final UpdateSettingsStore settingsStore;
    private final Clock clock;
    private final Supplier<Optional<InstallationLayout>> installationSupplier;

    public UpdateService() {
        this(new GitHubReleaseClient(), new UpdateSettingsStore(), Clock.systemUTC(), InstallationLayout::detect);
    }

    public UpdateService(
            ReleaseClient releaseClient,
            UpdateSettingsStore settingsStore,
            Clock clock,
            Supplier<Optional<InstallationLayout>> installationSupplier
    ) {
        this.releaseClient = releaseClient;
        this.settingsStore = settingsStore;
        this.clock = clock;
        this.installationSupplier = installationSupplier;
    }

    public UpdateSettings settings() {
        return settingsStore.load();
    }

    public Path settingsPath() {
        return settingsStore.storePath();
    }

    public Optional<Path> installRoot() {
        return installationSupplier.get().map(InstallationLayout::appRoot);
    }

    public boolean hasManagedInstallation() {
        return installationSupplier.get().isPresent();
    }

    public boolean autoUpdatesEnabled() {
        Boolean override = resolveBooleanOverride("JAIPILOT_AUTO_UPDATES", "jaipilot.auto.updates");
        if (override != null) {
            return override;
        }
        return settings().autoUpdatesEnabled(DEFAULT_AUTO_UPDATES);
    }

    public void setAutoUpdates(boolean enabled) {
        UpdateSettings current = settings();
        settingsStore.save(new UpdateSettings(enabled, current.lastCheckEpochSecond()));
    }

    public boolean shouldCheckForUpdatesNow() {
        UpdateSettings settings = settings();
        Long lastCheck = settings.lastCheckEpochSecond();
        return lastCheck == null || Instant.now(clock).getEpochSecond() - lastCheck >= STARTUP_CHECK_INTERVAL_SECONDS;
    }

    public UpdateCheckResult checkForUpdates(Duration timeout) {
        String currentVersion = VersionComparator.normalize(JaiPilotVersionProvider.resolveVersion());
        String latestVersion = releaseClient.fetchLatestVersion(timeout);
        markChecked();

        Optional<InstallationLayout> installation = installationSupplier.get();
        return new UpdateCheckResult(
                currentVersion,
                latestVersion,
                VersionComparator.compare(latestVersion, currentVersion) > 0,
                installation.isPresent(),
                autoUpdatesEnabled(),
                installation.map(InstallationLayout::appRoot).orElse(null)
        );
    }

    public UpdateInstallResult installLatest(Duration timeout) {
        String latestVersion = releaseClient.fetchLatestVersion(timeout);
        markChecked();
        return installVersion(latestVersion, timeout);
    }

    public UpdateInstallResult installVersion(String version, Duration timeout) {
        InstallationLayout installation = installationSupplier.get()
                .orElseThrow(() -> new IllegalStateException(
                        "Self-update is only available for JAIPilot installs managed by the installer."
                ));

        String currentVersion = VersionComparator.normalize(JaiPilotVersionProvider.resolveVersion());
        String targetVersion = VersionComparator.normalize(version);

        if (!installation.legacyLayout() && VersionComparator.compare(currentVersion, targetVersion) == 0) {
            return new UpdateInstallResult(
                    currentVersion,
                    targetVersion,
                    false,
                    false,
                    installation.appRoot()
            );
        }

        downloadAndInstall(installation, targetVersion, timeout);
        return new UpdateInstallResult(
                currentVersion,
                targetVersion,
                true,
                installation.legacyLayout(),
                installation.appRoot()
        );
    }

    public static boolean updateChecksDisabled() {
        Boolean override = resolveBooleanOverride("JAIPILOT_DISABLE_UPDATE_CHECK", "jaipilot.disable.update.check");
        return Boolean.TRUE.equals(override);
    }

    private void markChecked() {
        UpdateSettings current = settings();
        settingsStore.save(new UpdateSettings(current.autoUpdates(), Instant.now(clock).getEpochSecond()));
    }

    private void downloadAndInstall(InstallationLayout installation, String version, Duration timeout) {
        String platform = resolvePlatform();
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("jaipilot-update");
            Path archive = tempDir.resolve("jaipilot.tar.gz");
            Path checksum = tempDir.resolve("jaipilot.tar.gz.sha256");

            releaseClient.download(releaseClient.archiveUri(version, platform), archive, timeout);
            releaseClient.download(releaseClient.checksumUri(version, platform), checksum, timeout);

            String expectedSha256 = readExpectedSha256(checksum);
            String actualSha256 = computeSha256(archive);
            if (!expectedSha256.equals(actualSha256)) {
                throw new IllegalStateException("Downloaded update archive did not match the published SHA-256 checksum.");
            }

            Path extractRoot = tempDir.resolve("extract");
            Files.createDirectories(extractRoot);
            runCommand(timeout, List.of("tar", "-xzf", archive.toString(), "-C", extractRoot.toString()));

            Path extractedDir = findExtractedDir(extractRoot);
            validateExtractedDir(extractedDir);

            Path versionsDir = installation.versionsDir();
            Files.createDirectories(versionsDir);
            Path targetVersionDir = versionsDir.resolve(version);
            deleteRecursively(targetVersionDir);

            runCommand(timeout, List.of("mv", extractedDir.toString(), targetVersionDir.toString()));
            writeStableLauncher(installation.appRoot());
            switchCurrentSymlink(installation.appRoot(), version);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to install JAIPilot " + version + ".", exception);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private String resolvePlatform() {
        return resolveOs() + "-" + resolveArch();
    }

    private String resolveOs() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("mac")) {
            return "macos";
        }
        if (osName.contains("linux")) {
            return "linux";
        }
        throw new IllegalStateException("Unsupported operating system for self-update: " + System.getProperty("os.name", "unknown"));
    }

    private String resolveArch() {
        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if ("x86_64".equals(osArch) || "amd64".equals(osArch)) {
            return "x64";
        }
        if ("aarch64".equals(osArch) || "arm64".equals(osArch)) {
            return "aarch64";
        }
        throw new IllegalStateException("Unsupported architecture for self-update: " + System.getProperty("os.arch", "unknown"));
    }

    private String readExpectedSha256(Path checksumFile) throws IOException {
        try (Stream<String> lines = Files.lines(checksumFile, StandardCharsets.UTF_8)) {
            String firstToken = lines
                    .map(String::trim)
                    .filter(line -> !line.isBlank())
                    .map(line -> line.split("\\s+")[0].toLowerCase(Locale.ROOT))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Update checksum file was empty."));
            if (!firstToken.matches("[0-9a-f]{64}")) {
                throw new IllegalStateException("Update checksum file did not contain a valid SHA-256 digest.");
            }
            return firstToken;
        }
    }

    private String computeSha256(Path archive) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = Files.readAllBytes(archive);
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available on this JVM.", exception);
        }
    }

    private Path findExtractedDir(Path extractRoot) throws IOException {
        try (Stream<Path> children = Files.list(extractRoot)) {
            return children
                    .filter(Files::isDirectory)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("The downloaded update archive did not unpack correctly."));
        }
    }

    private void validateExtractedDir(Path extractedDir) {
        if (!Files.isRegularFile(extractedDir.resolve("lib").resolve("jaipilot.jar"))) {
            throw new IllegalStateException("The downloaded update archive is missing lib/jaipilot.jar.");
        }
        if (!Files.isExecutable(extractedDir.resolve("bin").resolve("jaipilot"))) {
            throw new IllegalStateException("The downloaded update archive is missing bin/jaipilot.");
        }
        if (!Files.isExecutable(extractedDir.resolve("runtime").resolve("bin").resolve("java"))) {
            throw new IllegalStateException("The downloaded update archive is missing the bundled runtime.");
        }
    }

    private void writeStableLauncher(Path appRoot) throws IOException {
        Path launcherDir = appRoot.resolve("bin");
        Files.createDirectories(launcherDir);
        Path launcher = launcherDir.resolve("jaipilot");
        String content = """
                #!/usr/bin/env sh
                set -eu

                BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
                exec "$BASE_DIR/current/bin/jaipilot" "$@"
                """;
        Files.writeString(
                launcher,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
        try {
            Files.setPosixFilePermissions(launcher, EXECUTABLE_FILE_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
            launcher.toFile().setExecutable(true, false);
        }
    }

    private void switchCurrentSymlink(Path appRoot, String version) throws IOException {
        Path currentLink = appRoot.resolve("current");
        Path tempLink = appRoot.resolve("current.tmp");
        Files.deleteIfExists(tempLink);
        Files.createSymbolicLink(tempLink, Path.of("versions", version));
        try {
            Files.move(tempLink, currentLink, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            Files.move(tempLink, currentLink, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void runCommand(Duration timeout, List<String> command) throws IOException {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output;
        try {
            boolean finished = process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("Timed out while running: " + String.join(" ", command));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IllegalStateException("Interrupted while running: " + String.join(" ", command), exception);
        }

        if (process.exitValue() != 0) {
            String suffix = output.isBlank() ? "" : " Output: " + output;
            throw new IllegalStateException("Command failed: " + String.join(" ", command) + "." + suffix);
        }
    }

    private void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(candidate -> {
                try {
                    Files.deleteIfExists(candidate);
                } catch (IOException ignored) {
                    // Best-effort cleanup for temporary update staging files.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup for temporary update staging files.
        }
    }

    private static Boolean resolveBooleanOverride(String environmentKey, String propertyKey) {
        String value = System.getenv(environmentKey);
        if (value == null || value.isBlank()) {
            value = System.getProperty(propertyKey);
        }
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("1".equals(normalized) || "true".equals(normalized) || "yes".equals(normalized) || "on".equals(normalized)) {
            return true;
        }
        if ("0".equals(normalized) || "false".equals(normalized) || "no".equals(normalized) || "off".equals(normalized)) {
            return false;
        }
        return null;
    }
}
