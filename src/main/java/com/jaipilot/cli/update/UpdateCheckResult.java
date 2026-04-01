package com.jaipilot.cli.update;

import java.nio.file.Path;

public record UpdateCheckResult(
        String currentVersion,
        String latestVersion,
        boolean updateAvailable,
        boolean managedInstall,
        boolean autoUpdatesEnabled,
        Path installRoot
) {
}
