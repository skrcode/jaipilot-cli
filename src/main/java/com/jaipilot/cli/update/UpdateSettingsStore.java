package com.jaipilot.cli.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaipilot.cli.util.JaipilotPaths;
import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

public final class UpdateSettingsStore {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");

    private final Path storePath;

    public UpdateSettingsStore() {
        this(resolveStorePath());
    }

    UpdateSettingsStore(Path storePath) {
        this.storePath = storePath;
    }

    public UpdateSettings load() {
        try {
            if (!Files.isRegularFile(storePath)) {
                return new UpdateSettings(null, null);
            }
            UpdateSettings settings = OBJECT_MAPPER.readValue(storePath.toFile(), UpdateSettings.class);
            return settings == null ? new UpdateSettings(null, null) : settings;
        } catch (IOException exception) {
            return new UpdateSettings(null, null);
        }
    }

    public void save(UpdateSettings settings) {
        Path tempFile = null;
        try {
            Path parent = storePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
                applyOwnerOnlyPermissions(parent, true);
            }

            byte[] serialized = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(settings);
            tempFile = Files.createTempFile(
                    parent != null ? parent : Path.of(System.getProperty("user.dir")),
                    storePath.getFileName().toString(),
                    ".tmp"
            );
            Files.write(tempFile, serialized);
            applyOwnerOnlyPermissions(tempFile, false);
            moveIntoPlace(tempFile);
            applyOwnerOnlyPermissions(storePath, false);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to save update settings to " + storePath, exception);
        } finally {
            cleanupTempFile(tempFile);
        }
    }

    public Path storePath() {
        return storePath;
    }

    private static Path resolveStorePath() {
        return JaipilotPaths.resolveConfigHome().resolve("update-settings.json");
    }

    private void moveIntoPlace(Path tempFile) throws IOException {
        try {
            Files.move(tempFile, storePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(tempFile, storePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void cleanupTempFile(Path tempFile) {
        if (tempFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException ignored) {
            // The final settings file has already been written or the original failure is more important.
        }
    }

    private void applyOwnerOnlyPermissions(Path path, boolean directory) {
        if (path == null || !Files.exists(path)) {
            return;
        }

        try {
            Files.setPosixFilePermissions(path, directory ? DIRECTORY_PERMISSIONS : FILE_PERMISSIONS);
            return;
        } catch (UnsupportedOperationException | IOException ignored) {
            // Fall back to best-effort owner-only permissions below.
        }

        File file = path.toFile();
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setExecutable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
        if (directory) {
            file.setExecutable(true, true);
        }
    }
}
