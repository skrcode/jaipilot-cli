package com.jaipilot.cli.update;

import java.io.PrintWriter;
import java.time.Duration;

public final class UpdateBootstrap {

    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration INSTALL_TIMEOUT = Duration.ofSeconds(30);

    private UpdateBootstrap() {
    }

    public static void maybeRun(String[] args, PrintWriter err) {
        if (err == null || UpdateService.updateChecksDisabled() || shouldSkip(args)) {
            return;
        }

        UpdateService updateService = new UpdateService();
        if (!updateService.hasManagedInstallation() || !updateService.shouldCheckForUpdatesNow()) {
            return;
        }

        try {
            UpdateCheckResult result = updateService.checkForUpdates(CHECK_TIMEOUT);
            if (!result.updateAvailable()) {
                return;
            }

            if (updateService.autoUpdatesEnabled()) {
                UpdateInstallResult installResult = updateService.installVersion(result.latestVersion(), INSTALL_TIMEOUT);
                if (installResult.changed()) {
                    err.println(
                            "JAIPilot updated to "
                                    + installResult.installedVersion()
                                    + ". Future invocations will use the new version."
                    );
                    err.flush();
                }
                return;
            }

            err.println(
                    "A new JAIPilot version is available: "
                            + result.latestVersion()
                            + ". Run `jaipilot update` to install it."
            );
            err.flush();
        } catch (RuntimeException exception) {
            // Update checks should never block the main command path.
        }
    }

    private static boolean shouldSkip(String[] args) {
        if (args == null || args.length == 0) {
            return false;
        }
        for (String arg : args) {
            if ("update".equals(arg) || "--help".equals(arg) || "-h".equals(arg) || "--version".equals(arg)) {
                return true;
            }
        }
        return false;
    }
}
