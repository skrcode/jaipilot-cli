package com.jaipilot.cli.util;

import java.nio.file.Path;

public final class JaipilotPaths {

    private JaipilotPaths() {
    }

    public static Path resolveConfigHome() {
        String override = System.getenv("JAIPILOT_CONFIG_HOME");
        if (override == null || override.isBlank()) {
            override = System.getProperty("jaipilot.config.home");
        }
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), ".config", "jaipilot");
    }
}
