package com.jaipilot.cli.update;

import com.jaipilot.cli.JaiPilotCli;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

final class InstallationLayout {

    private final Path appRoot;
    private final Path activePayloadDir;
    private final boolean legacyLayout;

    private InstallationLayout(Path appRoot, Path activePayloadDir, boolean legacyLayout) {
        this.appRoot = appRoot;
        this.activePayloadDir = activePayloadDir;
        this.legacyLayout = legacyLayout;
    }

    static Optional<InstallationLayout> detect() {
        String override = System.getProperty("jaipilot.install.codeSource");
        if (override != null && !override.isBlank()) {
            return detect(Path.of(override));
        }

        try {
            return detect(Path.of(
                    JaiPilotCli.class.getProtectionDomain().getCodeSource().getLocation().toURI()
            ));
        } catch (URISyntaxException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    static Optional<InstallationLayout> detect(Path codeSourcePath) {
        Path normalized = codeSourcePath.toAbsolutePath().normalize();
        if (!"jaipilot.jar".equals(fileName(normalized))) {
            return Optional.empty();
        }

        Path libDir = normalized.getParent();
        if (libDir == null || !"lib".equals(fileName(libDir))) {
            return Optional.empty();
        }

        Path payloadDir = libDir.getParent();
        if (payloadDir == null) {
            return Optional.empty();
        }

        Path versionsDir = payloadDir.getParent();
        if (versionsDir != null && "versions".equals(fileName(versionsDir))) {
            Path appRoot = versionsDir.getParent();
            if (appRoot != null) {
                return Optional.of(new InstallationLayout(appRoot, payloadDir, false));
            }
        }

        if (Files.isExecutable(payloadDir.resolve("bin").resolve("jaipilot"))
                && Files.isExecutable(payloadDir.resolve("runtime").resolve("bin").resolve("java"))) {
            return Optional.of(new InstallationLayout(payloadDir, payloadDir, true));
        }

        return Optional.empty();
    }

    Path appRoot() {
        return appRoot;
    }

    Path activePayloadDir() {
        return activePayloadDir;
    }

    boolean legacyLayout() {
        return legacyLayout;
    }

    Path versionsDir() {
        return appRoot.resolve("versions");
    }

    Path currentSymlink() {
        return appRoot.resolve("current");
    }

    Path stableLauncher() {
        return appRoot.resolve("bin").resolve("jaipilot");
    }

    private static String fileName(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? "" : fileName.toString();
    }
}
