package com.jaipilot.cli.update;

import java.nio.file.Path;

public record UpdateInstallResult(
        String previousVersion,
        String installedVersion,
        boolean changed,
        boolean migratedLegacyLayout,
        Path installRoot
) {
}
